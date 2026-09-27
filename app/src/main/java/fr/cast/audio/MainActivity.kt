package fr.cast.audio

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import fr.cast.audio.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager
    private var castContext: CastContext? = null
    private var castSession: CastSession? = null

    /** URL actuellement envoyée à l'enceinte Cast, pour ne pas la recharger inutilement. */
    private var loadedUrl: String? = null

    private val stateListener: (StreamState.Snapshot) -> Unit = { render(it) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.RECORD_AUDIO] == true || hasRecordPermission()) {
                requestProjection()
            } else {
                Toast.makeText(this, R.string.permission_audio_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this, AudioCaptureService.startIntent(this, result.resultCode, data)
                )
            } else {
                Toast.makeText(this, R.string.projection_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) =
            onCastConnected(session, autoStart = true)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onCastConnected(session)
        override fun onSessionEnded(session: CastSession, error: Int) = onCastDisconnected()
        override fun onSessionSuspended(session: CastSession, reason: Int) = onCastDisconnected()
        override fun onSessionStartFailed(session: CastSession, error: Int) = onCastDisconnected()
        override fun onSessionResumeFailed(session: CastSession, error: Int) = onCastDisconnected()
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = renderBluetooth()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = renderBluetooth()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        audioManager = getSystemService(AudioManager::class.java)

        castContext = try {
            @Suppress("DEPRECATION")
            CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            Log.w(TAG, "Google Cast indisponible (services Google Play absents ?)", e)
            null
        }
        if (castContext != null) {
            CastButtonFactory.setUpMediaRouteButton(applicationContext, binding.castButton)
        } else {
            binding.castButton.visibility = View.GONE
            binding.castUnavailable.visibility = View.VISIBLE
        }

        binding.toggleButton.setOnClickListener {
            if (StreamState.current.running) {
                stopCasting()
            } else {
                startCapture()
            }
        }
        binding.copyUrlButton.setOnClickListener { copyUrl() }
        binding.outputSwitcherButton.setOnClickListener { openOutputSwitcher() }
        binding.bluetoothSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        binding.outputSwitcherButton.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        StreamState.addListener(stateListener)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        castContext?.sessionManager?.let { manager ->
            manager.addSessionManagerListener(sessionListener, CastSession::class.java)
            manager.currentCastSession?.let { onCastConnected(it) }
        }
        renderBluetooth()
    }

    override fun onStop() {
        StreamState.removeListener(stateListener)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onStop()
    }

    // --- Capture -------------------------------------------------------------------------------

    private fun hasRecordPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startCapture() {
        val needed = mutableListOf<String>()
        if (!hasRecordPermission()) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) requestProjection() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Capture de tout l'appareil : le mode "une seule appli" n'a pas de sens pour l'audio.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun stopCasting() {
        castSession?.remoteMediaClient?.stop()
        loadedUrl = null
        AudioCaptureService.stop(this)
    }

    // --- Google Cast ---------------------------------------------------------------------------

    private fun onCastConnected(session: CastSession, autoStart: Boolean = false) {
        castSession = session
        val state = StreamState.current
        if (state.running) {
            loadOnCastDevice(state.streamUrl)
        } else if (autoStart) {
            // On vient de choisir une enceinte : on lance directement la capture.
            loadedUrl = null
            startCapture()
        }
        renderCast()
    }

    private fun onCastDisconnected() {
        castSession = null
        loadedUrl = null
        renderCast()
    }

    private fun loadOnCastDevice(url: String?) {
        val client = castSession?.remoteMediaClient ?: return
        if (url == null || url == loadedUrl) return
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, getString(R.string.cast_title))
            putString(MediaMetadata.KEY_ARTIST, Build.MODEL)
        }
        val media = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType("audio/aac")
            .setMetadata(metadata)
            .build()
        client.load(MediaLoadRequestData.Builder().setMediaInfo(media).setAutoplay(true).build())
            .setResultCallback { result ->
                if (!result.status.isSuccess) {
                    loadedUrl = null
                    Toast.makeText(this, getString(R.string.cast_load_failed, result.status.statusCode), Toast.LENGTH_LONG).show()
                }
            }
        loadedUrl = url
    }

    // --- Bluetooth -----------------------------------------------------------------------------

    private fun openOutputSwitcher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (MediaRouter2.getInstance(this).showSystemOutputSwitcher()) return
        }
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun renderBluetooth() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in BLUETOOTH_OUTPUT_TYPES }
            .map { it.productName.toString() }
            .distinct()
        binding.bluetoothStatus.text = if (devices.isEmpty()) {
            getString(R.string.bluetooth_none)
        } else {
            getString(R.string.bluetooth_connected, devices.joinToString(", "))
        }
    }

    // --- Interface -----------------------------------------------------------------------------

    private fun render(state: StreamState.Snapshot) {
        binding.toggleButton.setText(if (state.running) R.string.stop else R.string.start)
        binding.statusText.text = when {
            state.error != null -> getString(R.string.status_error, state.error)
            !state.running -> getString(R.string.status_idle)
            state.streamUrl == null -> getString(R.string.notification_no_wifi)
            else -> resources.getQuantityString(R.plurals.status_running, state.clients, state.clients)
        }
        binding.urlText.text = state.streamUrl ?: "—"
        binding.copyUrlButton.isEnabled = state.streamUrl != null
        if (state.running) loadOnCastDevice(state.streamUrl) else loadedUrl = null
        renderCast()
    }

    private fun renderCast() {
        val name = castSession?.castDevice?.friendlyName
        binding.castStatus.text = if (name != null) {
            getString(R.string.cast_connected, name)
        } else {
            getString(R.string.cast_hint)
        }
    }

    private fun copyUrl() {
        val url = StreamState.current.streamUrl ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AudioCast", url))
        Toast.makeText(this, R.string.url_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MainActivity"

        private val BLUETOOTH_OUTPUT_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        }
    }
}
