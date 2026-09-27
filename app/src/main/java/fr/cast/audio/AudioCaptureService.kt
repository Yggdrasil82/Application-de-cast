package fr.cast.audio

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Service au premier plan qui capture le son joué par les autres applications
 * (API AudioPlaybackCapture, Android 10+), l'encode en AAC et le diffuse via [StreamServer].
 */
class AudioCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var server: StreamServer? = null
    private var captureThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var capturing = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection arrêtée par le système")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (capturing) return START_NOT_STICKY // déjà au premier plan
        // Lancé via startForegroundService : il faut passer au premier plan dans tous les cas,
        // et (Android 14+) AVANT d'obtenir la MediaProjection.
        startInForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            start(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "Impossible de démarrer la capture", e)
            StreamState.update { it.copy(running = false, error = e.message ?: e.toString()) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification(getString(R.string.notification_starting))
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO est vérifiée par MainActivity avant de lancer le service.
    private fun start(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection indisponible")
        mp.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        projection = mp

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, FRAME_BYTES * 8))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord non initialisé")
        }

        val ip = NetworkUtils.localIpv4(this)
        val streamServer = StreamServer(PORT) { count ->
            StreamState.update { it.copy(clients = count) }
        }
        try {
            streamServer.start()
        } catch (e: IOException) {
            record.release()
            throw IllegalStateException("Port $PORT indisponible", e)
        }
        server = streamServer

        acquireLocks()
        capturing = true
        captureThread = thread(name = "audio-capture", priority = Thread.MAX_PRIORITY) {
            captureLoop(record, streamServer)
        }

        val baseUrl = ip?.let { "http://$it:$PORT" }
        StreamState.update { it.copy(running = true, baseUrl = baseUrl, error = null) }
        val url = StreamState.current.streamUrl
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(url?.let { getString(R.string.notification_running, it) }
                ?: getString(R.string.notification_no_wifi)),
        )
    }

    private fun captureLoop(record: AudioRecord, streamServer: StreamServer) {
        val encoder = AacEncoder(SAMPLE_RATE, CHANNELS, BIT_RATE) { frame ->
            streamServer.broadcast(StreamFormat.AAC, frame)
        }
        // Le PCM brut alimente à la fois l'encodeur AAC et le flux WAV (format imposé par la norme DLNA).
        fun feed(pcm: ByteArray, length: Int) {
            encoder.encode(pcm, length)
            if (streamServer.hasClients(StreamFormat.WAV)) {
                streamServer.broadcast(StreamFormat.WAV, pcm.copyOf(length))
            }
        }
        val buffer = ByteArray(FRAME_BYTES)
        val silence = ByteArray(FRAME_BYTES)
        val bytesPerSecond = SAMPLE_RATE * CHANNELS * 2L
        val startNs = System.nanoTime()
        var fedBytes = 0L
        try {
            record.startRecording()
            while (capturing) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                if (read > 0) {
                    feed(buffer, read)
                    fedBytes += read
                    continue
                }
                if (read < 0) {
                    Log.w(TAG, "AudioRecord.read a renvoyé $read")
                    break
                }
                // Certains appareils ne fournissent rien quand aucun son n'est joué :
                // on injecte du silence pour que le flux reste continu (sinon l'enceinte coupe).
                val expected = (System.nanoTime() - startNs) * bytesPerSecond / 1_000_000_000L
                if (expected - fedBytes > bytesPerSecond / 5) {
                    feed(silence, silence.size)
                    fedBytes += silence.size
                } else {
                    Thread.sleep(5)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.e(TAG, "Erreur de capture", e)
            StreamState.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
            encoder.release()
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioCast:capture")
            .apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AudioCast:wifi")
            .apply { acquire() }
    }

    override fun onDestroy() {
        capturing = false
        captureThread?.interrupt()
        captureThread?.join(1000)
        captureThread = null
        server?.stop()
        server = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        StreamState.update { it.copy(running = false, baseUrl = null, clients = 0) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "fr.cast.audio.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        const val PORT = 8765
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BIT_RATE = 192_000

        /** 1024 échantillons stéréo 16 bits = une trame AAC. */
        private const val FRAME_BYTES = 1024 * CHANNELS * 2

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, AudioCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stop(context: Context) {
            context.startService(Intent(context, AudioCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
