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
import com.google.android.material.button.MaterialButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import fr.cast.audio.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager
    private var castContext: CastContext? = null
    private var castSession: CastSession? = null

    /** URL actuellement envoyée à l'enceinte Cast, pour ne pas la recharger inutilement. */
    private var loadedUrl: String? = null

    /** Format envoyé à l'enceinte Cast : WAV d'abord (officiellement pris en charge), AAC en secours. */
    private var castFormat = StreamFormat.WAV

    /** État du lecteur de l'enceinte Cast (lecture, mise en mémoire tampon, erreur…). */
    private var castPlayerText: String? = null

    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val status = castSession?.remoteMediaClient?.mediaStatus ?: return
            when (status.playerState) {
                MediaStatus.PLAYER_STATE_PLAYING ->
                    castPlayerText = getString(R.string.cast_state_playing, castFormat.name)
                MediaStatus.PLAYER_STATE_BUFFERING ->
                    castPlayerText = getString(R.string.cast_state_buffering)
                MediaStatus.PLAYER_STATE_IDLE ->
                    if (status.idleReason == MediaStatus.IDLE_REASON_ERROR && loadedUrl != null) {
                        onCastPlaybackError(getString(R.string.cast_error_player))
                    }
            }
            renderCast()
        }
    }

    private val stateListener: (StreamState.Snapshot) -> Unit = { render(it) }

    /** Opérations réseau DLNA (découverte, commandes SOAP), exécutées hors du thread principal. */
    private val dlnaExecutor = Executors.newSingleThreadExecutor()
    private var dlnaRenderers: List<DlnaRenderer> = emptyList()

    /** Lecteur DLNA choisi alors que la capture n'était pas encore démarrée. */
    private var dlnaPending: DlnaRenderer? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.RECORD_AUDIO] == true || hasRecordPermission()) {
                requestProjection()
            } else {
                dlnaPending = null
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
                dlnaPending = null
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
        binding.dlnaSearchButton.setOnClickListener { searchDlna() }
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
        castSession?.remoteMediaClient?.unregisterCallback(remoteCallback)
        StreamState.removeListener(stateListener)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onStop()
    }

    override fun onDestroy() {
        dlnaExecutor.shutdown()
        super.onDestroy()
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
        activeDlna?.let { renderer ->
            dlnaExecutor.execute { runCatching { Dlna.stop(renderer) } }
        }
        activeDlna = null
        dlnaPending = null
        renderDlnaList()
        AudioCaptureService.stop(this)
    }

    // --- Google Cast ---------------------------------------------------------------------------

    private fun onCastConnected(session: CastSession, autoStart: Boolean = false) {
        castSession?.remoteMediaClient?.unregisterCallback(remoteCallback)
        castSession = session
        session.remoteMediaClient?.registerCallback(remoteCallback)
        if (autoStart) {
            castFormat = StreamFormat.WAV
            castPlayerText = null
        }
        val state = StreamState.current
        if (state.running) {
            loadOnCastDevice(state.baseUrl)
        } else if (autoStart) {
            // On vient de choisir une enceinte : on lance directement la capture.
            loadedUrl = null
            startCapture()
        }
        renderCast()
    }

    private fun onCastDisconnected() {
        castSession?.remoteMediaClient?.unregisterCallback(remoteCallback)
        castSession = null
        loadedUrl = null
        castFormat = StreamFormat.WAV
        castPlayerText = null
        renderCast()
    }

    /** L'enceinte n'arrive pas à lire le flux : on retente une fois dans l'autre format. */
    private fun onCastPlaybackError(reason: String) {
        Log.w(TAG, "Lecture Cast en échec ($castFormat) : $reason")
        loadedUrl = null
        if (castFormat == StreamFormat.WAV) {
            castFormat = StreamFormat.AAC
            castPlayerText = getString(R.string.cast_state_retry)
            loadOnCastDevice(StreamState.current.baseUrl)
        } else {
            castPlayerText = getString(R.string.cast_state_failed, reason)
        }
        renderCast()
    }

    private fun loadOnCastDevice(baseUrl: String?) {
        val client = castSession?.remoteMediaClient ?: return
        if (baseUrl == null) return
        val url = baseUrl + castFormat.path
        if (url == loadedUrl) return
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, getString(R.string.cast_title))
            putString(MediaMetadata.KEY_ARTIST, Build.MODEL)
        }
        val media = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(castFormat.mimeType)
            .setMetadata(metadata)
            .build()
        client.load(MediaLoadRequestData.Builder().setMediaInfo(media).setAutoplay(true).build())
            .setResultCallback { result ->
                if (!result.status.isSuccess && loadedUrl == url) {
                    onCastPlaybackError(getString(R.string.cast_load_failed, result.status.statusCode))
                }
            }
        loadedUrl = url
        castPlayerText = getString(R.string.cast_state_loading)
    }

    // --- DLNA / UPnP ----------------------------------------------------------------------------

    private fun searchDlna() {
        binding.dlnaSearchButton.isEnabled = false
        binding.dlnaStatus.setText(R.string.dlna_searching)
        dlnaExecutor.execute {
            val found = try {
                Dlna.discover(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "Recherche DLNA impossible", e)
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.dlnaSearchButton.isEnabled = true
                dlnaRenderers = found
                binding.dlnaStatus.setText(if (found.isEmpty()) R.string.dlna_none else R.string.dlna_found)
                renderDlnaList()
            }
        }
    }

    private fun renderDlnaList() {
        binding.dlnaList.removeAllViews()
        for (renderer in dlnaRenderers) {
            val button = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle)
            val active = renderer.udn == activeDlna?.udn
            button.text = if (active) "▶ ${renderer.name}" else renderer.name
            button.isAllCaps = false
            button.setOnClickListener { selectDlna(renderer) }
            binding.dlnaList.addView(button)
        }
    }

    private fun selectDlna(renderer: DlnaRenderer) {
        if (StreamState.current.running) {
            playOnDlna(renderer)
        } else {
            dlnaPending = renderer
            startCapture()
        }
    }

    private fun playOnDlna(renderer: DlnaRenderer) {
        val baseUrl = StreamState.current.baseUrl
        if (baseUrl == null) {
            binding.dlnaStatus.setText(R.string.dlna_no_wifi)
            return
        }
        binding.dlnaStatus.text = getString(R.string.dlna_connecting, renderer.name)
        val title = getString(R.string.cast_title)
        dlnaExecutor.execute {
            val result = runCatching { Dlna.play(renderer, baseUrl, title) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { format ->
                    activeDlna = renderer
                    binding.dlnaStatus.text = getString(R.string.dlna_playing, renderer.name, format.name)
                }.onFailure { e ->
                    Log.w(TAG, "Lecture DLNA refusée", e)
                    binding.dlnaStatus.text = getString(R.string.dlna_failed, renderer.name, e.message ?: e.toString())
                }
                renderDlnaList()
            }
        }
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
        renderLevel(state)
        if (state.running) loadOnCastDevice(state.baseUrl) else loadedUrl = null
        if (state.running) {
            dlnaPending?.let { renderer ->
                dlnaPending = null
                playOnDlna(renderer)
            }
        } else if (activeDlna != null) {
            activeDlna = null
            binding.dlnaStatus.setText(R.string.dlna_hint)
            renderDlnaList()
        }
        renderCast()
    }

    private fun renderLevel(state: StreamState.Snapshot) {
        binding.levelGroup.visibility = if (state.running) View.VISIBLE else View.GONE
        val percent = (state.level * 100).toInt().coerceIn(0, 100)
        binding.levelBar.progress = percent
        binding.levelText.setText(if (percent > 0) R.string.level_ok else R.string.level_silent)
    }

    private fun renderCast() {
        val name = castSession?.castDevice?.friendlyName
        binding.castStatus.text = if (name != null) {
            listOfNotNull(getString(R.string.cast_connected, name), castPlayerText).joinToString("\n")
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

        /** Lecteur DLNA en cours de lecture (conservé si l'activité est recréée). */
        private var activeDlna: DlnaRenderer? = null

        private val BLUETOOTH_OUTPUT_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        }
    }
}
