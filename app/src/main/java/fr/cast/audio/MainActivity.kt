package fr.cast.audio

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import fr.cast.audio.databinding.ActivityMainBinding
import fr.cast.audio.databinding.ItemSpeakerBinding
import java.io.IOException
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    /** Une enceinte de la liste, quel que soit son protocole. */
    private data class Speaker(
        val id: String,
        val name: String,
        val kind: Kind,
        val state: State,
        val detail: String,
        /** Volume de 0 à 100 si l'enceinte est active et réglable, sinon null. */
        val volume: Int? = null,
    ) {
        enum class Kind { CAST, DLNA, AIRPLAY }
        enum class State { IDLE, CONNECTING, ACTIVE }
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val stateListener: (StreamState.Snapshot) -> Unit = { render(it) }

    // --- Google Cast ---
    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private var mediaRouter: MediaRouter? = null
    private val castSelector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()

    /** URL actuellement envoyée à l'enceinte Cast, pour ne pas la recharger inutilement. */
    private var loadedUrl: String? = null

    /** Format envoyé à l'enceinte Cast : WAV d'abord (officiellement pris en charge), AAC en secours. */
    private var castFormat = StreamFormat.WAV

    /** État du lecteur de l'enceinte Cast (lecture, mise en mémoire tampon, erreur…). */
    private var castPlayerText: String? = null

    // --- DLNA ---
    /** Opérations réseau DLNA (découverte, commandes SOAP), exécutées hors du thread principal. */
    private val dlnaExecutor = Executors.newSingleThreadExecutor()
    private var dlnaRenderers: List<DlnaRenderer> = emptyList()
    private var dlnaSearching = false
    private var dlnaSearched = false

    /** Lecteur DLNA en cours de connexion, et message affiché sous chaque lecteur. */
    private var dlnaConnecting: String? = null
    private val dlnaMessages = mutableMapOf<String, String>()

    /** Lecteur DLNA (et format imposé éventuel) choisi alors que la capture n'était pas démarrée. */
    private var dlnaPending: DlnaRenderer? = null
    private var dlnaPendingOffer: DlnaOffer? = null

    // --- AirPlay ---
    private lateinit var airplayDiscovery: AirPlayDiscovery
    private var airplayDevices: List<AirPlayDevice> = emptyList()

    /** Négociations AirPlay (réseau), hors du thread principal. */
    private val airplayExecutor = Executors.newSingleThreadExecutor()
    private var airplayConnecting: String? = null
    private val airplayMessages = mutableMapOf<String, String>()

    /** Récepteur AirPlay choisi alors que la capture n'était pas démarrée. */
    private var airplayPending: AirPlayDevice? = null

    /** Mode démonstration (builds debug) : données fictives pour les captures d'écran. */
    private var demo = false
    private var demoIdle = false

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
        override fun onSessionStarting(session: CastSession) = renderSpeakers()
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
    }

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
            renderSpeakers()
        }
    }

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = renderSpeakers()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = renderSpeakers()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = renderSpeakers()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) =
            renderSpeakers()
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) =
            renderSpeakers()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        // Son coupé par une diffusion interrompue brutalement : on le rétablit.
        if (!StreamState.current.running && prefs.mutedByApp) LocalMute.apply(this, mute = false)

        castContext = try {
            @Suppress("DEPRECATION")
            CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            Log.w(TAG, "Google Cast indisponible (services Google Play absents ?)", e)
            null
        }
        if (castContext != null) {
            mediaRouter = MediaRouter.getInstance(this)
        } else {
            binding.castUnavailable.visibility = View.VISIBLE
        }

        binding.toggleButton.setOnClickListener {
            if (StreamState.current.running) stopCasting() else startCapture()
        }
        binding.resyncButton.setOnClickListener { resync() }
        binding.resumeButton.setOnClickListener { resumeLastSpeaker() }
        binding.muteButton.setOnClickListener {
            val mute = !prefs.muteLocal
            prefs.muteLocal = mute
            if (StreamState.current.running) LocalMute.apply(this, mute)
            renderHero()
        }
        binding.refreshButton.setOnClickListener { searchDlna() }
        binding.copyUrlButton.setOnClickListener { copyUrl() }
        binding.copyLogButton.setOnClickListener { copyLog() }
        binding.shareLogButton.setOnClickListener { shareLog() }
        binding.advancedToggle.setOnClickListener {
            val show = binding.advancedGroup.visibility != View.VISIBLE
            binding.advancedGroup.visibility = if (show) View.VISIBLE else View.GONE
            binding.advancedToggle.setIconResource(if (show) R.drawable.ic_collapse else R.drawable.ic_expand)
        }

        airplayDiscovery = AirPlayDiscovery(this) { devices ->
            airplayDevices = devices
            renderSpeakers()
        }

        demo = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEMO, false)
        demoIdle = demo && intent.getBooleanExtra(EXTRA_DEMO_IDLE, false)
        if (demo) startDemo()
    }

    override fun onStart() {
        super.onStart()
        StreamState.addListener(stateListener)
        mediaRouter?.addCallback(castSelector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        castContext?.sessionManager?.let { manager ->
            manager.addSessionManagerListener(sessionListener, CastSession::class.java)
            manager.currentCastSession?.let { onCastConnected(it) }
        }
        if (!dlnaSearched && !demo) searchDlna()
        if (!demo) airplayDiscovery.start()
        renderSpeakers()
    }

    override fun onStop() {
        castSession?.remoteMediaClient?.unregisterCallback(remoteCallback)
        mediaRouter?.removeCallback(routerCallback)
        airplayDiscovery.stop()
        StreamState.removeListener(stateListener)
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onStop()
    }

    override fun onDestroy() {
        dlnaExecutor.shutdown()
        airplayExecutor.shutdown()
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

    /** Arrête tout : enceintes Cast, DLNA et AirPlay, puis la capture. */
    private fun stopCasting() {
        castSession?.remoteMediaClient?.stop()
        loadedUrl = null
        stopDlna()
        dlnaPending = null
        stopAirPlay()
        airplayPending = null
        AudioCaptureService.stop(this)
    }

    // --- Google Cast ---------------------------------------------------------------------------

    private fun castRoutes(): List<MediaRouter.RouteInfo> {
        val router = mediaRouter ?: return emptyList()
        return router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(castSelector) }
    }

    private fun onCastSpeakerClicked(route: MediaRouter.RouteInfo) {
        val router = mediaRouter ?: return
        if (router.selectedRoute.id == route.id) {
            // Deuxième appui : on arrête la diffusion sur cette enceinte.
            castContext?.sessionManager?.endCurrentSession(true)
        } else {
            castFormat = StreamFormat.WAV
            castPlayerText = null
            router.selectRoute(route) // le framework Cast ouvre alors la session
        }
        renderSpeakers()
    }

    private fun onCastConnected(session: CastSession, autoStart: Boolean = false) {
        castSession?.remoteMediaClient?.unregisterCallback(remoteCallback)
        castSession = session
        mediaRouter?.selectedRoute?.takeIf { it.matchesSelector(castSelector) }?.let {
            rememberSpeaker(CAST_PREFIX + it.id, it.name)
        }
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
        renderSpeakers()
    }

    private fun onCastDisconnected() {
        castSession?.remoteMediaClient?.unregisterCallback(remoteCallback)
        castSession = null
        loadedUrl = null
        castFormat = StreamFormat.WAV
        castPlayerText = null
        renderSpeakers()
    }

    /** L'enceinte n'arrive pas à lire le flux : on retente une fois dans l'autre format. */
    private fun onCastPlaybackError(reason: String) {
        StreamState.log("Google Cast : échec en ${castFormat.name} ($reason)")
        loadedUrl = null
        if (castFormat == StreamFormat.WAV) {
            castFormat = StreamFormat.AAC
            castPlayerText = getString(R.string.cast_state_retry)
            loadOnCastDevice(StreamState.current.baseUrl)
        } else {
            castPlayerText = getString(R.string.cast_state_failed, reason)
        }
        renderSpeakers()
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
        StreamState.log("Google Cast : envoi du flux ${castFormat.name} à ${castSession?.castDevice?.friendlyName}")
        renderSpeakers()
    }

    // --- DLNA / UPnP ----------------------------------------------------------------------------

    private fun searchDlna() {
        if (dlnaSearching) return
        dlnaSearching = true
        dlnaSearched = true
        renderSpeakers()
        dlnaExecutor.execute {
            val found = try {
                Dlna.discover(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "Recherche DLNA impossible", e)
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dlnaSearching = false
                // On garde le lecteur actif même s'il n'a pas répondu cette fois-ci.
                val active = activeDlna
                dlnaRenderers = if (active != null && found.none { it.udn == active.udn }) found + active else found
                renderSpeakers()
            }
        }
    }

    private fun onDlnaSpeakerClicked(renderer: DlnaRenderer) {
        when {
            dlnaConnecting == renderer.udn -> return
            activeDlna?.udn == renderer.udn -> {
                stopDlna()
                renderSpeakers()
            }
            else -> selectDlna(renderer, null)
        }
    }

    /** Appui long : choisir soi-même le format, si le choix automatique ne donne pas de son. */
    private fun chooseDlnaFormat(renderer: DlnaRenderer) {
        dlnaExecutor.execute {
            val offers = Dlna.offers(renderer, StreamState::log)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val labels = offers.map { "${it.format.name} (${it.mimeType})" }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.dlna_choose_format, renderer.name))
                    .setItems(labels) { _, which -> selectDlna(renderer, offers[which]) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** [offer] null : les formats sont essayés automatiquement jusqu'à ce que l'un marche. */
    private fun selectDlna(renderer: DlnaRenderer, offer: DlnaOffer?) {
        if (StreamState.current.running) {
            playOnDlna(renderer, offer)
        } else {
            dlnaPending = renderer
            dlnaPendingOffer = offer
            startCapture()
        }
    }

    private fun playOnDlna(renderer: DlnaRenderer, offer: DlnaOffer?) {
        val baseUrl = StreamState.current.baseUrl
        if (baseUrl == null) {
            dlnaMessages[renderer.udn] = getString(R.string.dlna_no_wifi)
            renderSpeakers()
            return
        }
        // Un seul lecteur DLNA à la fois : on libère le précédent.
        activeDlna?.takeIf { it.udn != renderer.udn }?.let { stopDlna() }
        dlnaConnecting = renderer.udn
        dlnaMessages[renderer.udn] = getString(R.string.dlna_connecting)
        renderSpeakers()
        val title = getString(R.string.cast_title)
        dlnaExecutor.execute {
            val result = runCatching {
                val offers = offer?.let { listOf(it) } ?: Dlna.offers(renderer, StreamState::log)
                Dlna.play(
                    renderer, baseUrl, title, offers,
                    isStreaming = { format -> AudioCaptureService.isStreaming(format) },
                    log = StreamState::log,
                )
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dlnaConnecting = null
                val chosen = result.getOrNull()
                if (chosen != null) {
                    activeDlna = renderer
                    activeDlnaOffer = chosen
                    dlnaMessages[renderer.udn] = getString(R.string.dlna_playing, chosen.format.name)
                    rememberSpeaker(DLNA_PREFIX + renderer.udn, renderer.name)
                } else {
                    val reason = result.exceptionOrNull()?.let { it.message ?: it.toString() }
                        ?: getString(R.string.dlna_no_format)
                    Log.w(TAG, "Lecture DLNA impossible : $reason")
                    dlnaMessages[renderer.udn] = getString(R.string.dlna_failed, reason)
                }
                renderSpeakers()
            }
        }
    }

    private fun stopDlna() {
        activeDlna?.let { renderer ->
            dlnaMessages.remove(renderer.udn)
            dlnaExecutor.execute { runCatching { Dlna.stop(renderer) } }
        }
        activeDlna = null
        activeDlnaOffer = null
    }

    // --- AirPlay (AirMedia) --------------------------------------------------------------------

    private fun onAirPlaySpeakerClicked(device: AirPlayDevice) {
        when {
            airplayConnecting == device.id -> return
            activeAirPlay?.id == device.id -> {
                stopAirPlay()
                renderSpeakers()
            }
            StreamState.current.running -> playOnAirPlay(device)
            else -> {
                airplayPending = device
                startCapture()
            }
        }
    }

    private fun playOnAirPlay(device: AirPlayDevice) {
        airplayConnecting = device.id
        airplayMessages[device.id] = getString(R.string.speaker_connecting)
        renderSpeakers()
        airplayExecutor.execute {
            val password = prefs.airplayPassword(device.id)
            val result = runCatching {
                AirPlaySessions.start(device, StreamState::log, password, prefs.airplayVolume)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                airplayConnecting = null
                result.onSuccess {
                    activeAirPlay = device
                    airplayMessages[device.id] = getString(R.string.airplay_playing)
                    rememberSpeaker(AIRPLAY_PREFIX + device.id, device.name)
                }.onFailure { e ->
                    val reason = e.message ?: e.toString()
                    StreamState.log("AirPlay : échec avec ${device.name} ($reason)")
                    airplayMessages[device.id] = getString(R.string.dlna_failed, reason)
                    if (e is AirPlayPasswordException) {
                        if (e.wrong) prefs.setAirplayPassword(device.id, null)
                        askAirPlayPassword(device, e.wrong)
                    }
                }
                renderSpeakers()
            }
        }
    }

    /** Demande le mot de passe AirMedia / AirPlay, le retient et relance la connexion. */
    private fun askAirPlayPassword(device: AirPlayDevice, wrong: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.airplay_password_hint)
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.airplay_password_title)
            .setMessage(
                getString(
                    if (wrong) R.string.airplay_password_wrong else R.string.airplay_password_message,
                    device.name,
                )
            )
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.setAirplayPassword(device.id, input.text.toString())
                playOnAirPlay(device)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setAirPlayVolume(percent: Int) {
        prefs.airplayVolume = percent
        airplayExecutor.execute {
            try {
                AirPlaySessions.active?.setVolume(percent)
            } catch (e: IOException) {
                StreamState.log("AirPlay : volume non appliqué (${e.message})")
            }
        }
    }

    private fun setCastVolume(percent: Int) {
        try {
            castSession?.volume = percent / 100.0
        } catch (e: Exception) {
            StreamState.log("Google Cast : volume non appliqué (${e.message})")
        }
    }

    // --- Dernière enceinte ---------------------------------------------------------------------

    private fun rememberSpeaker(id: String, name: String) {
        prefs.lastSpeakerId = id
        prefs.lastSpeakerName = name
    }

    /** Relance la diffusion sur la dernière enceinte utilisée, si elle est détectée. */
    private fun resumeLastSpeaker() {
        val id = prefs.lastSpeakerId ?: return
        val speaker = buildSpeakers().firstOrNull { it.id == id }
        if (speaker == null) {
            Toast.makeText(this, getString(R.string.resume_not_found, prefs.lastSpeakerName), Toast.LENGTH_LONG).show()
            return
        }
        onSpeakerClicked(speaker)
    }

    private fun stopAirPlay() {
        activeAirPlay?.let { airplayMessages.remove(it.id) }
        activeAirPlay = null
        AirPlaySessions.stop()
    }

    // --- Resynchronisation ---------------------------------------------------------------------

    /** Relance la lecture sur les enceintes : elles repartent du direct, sans le retard accumulé. */
    private fun resync() {
        StreamState.log(getString(R.string.resync_log))
        if (castSession != null) {
            loadedUrl = null
            loadOnCastDevice(StreamState.current.baseUrl)
        }
        val renderer = activeDlna
        val offer = activeDlnaOffer
        if (renderer != null && offer != null) playOnDlna(renderer, offer)
        activeAirPlay?.let { playOnAirPlay(it) }
    }

    // --- Interface -----------------------------------------------------------------------------

    private var lastRunning: Boolean? = null

    private fun render(state: StreamState.Snapshot) {
        val running = state.running
        renderHero(state)

        binding.urlText.text = state.streamUrl ?: "—"
        binding.copyUrlButton.isEnabled = state.streamUrl != null
        // À l'écran, seulement la fin du journal ; les boutons copier / partager donnent tout.
        binding.logText.text = state.log.takeLast(LOG_LINES_SHOWN).joinToString("\n").ifEmpty { "—" }

        if (demo) return
        if (running) {
            loadOnCastDevice(state.baseUrl)
            dlnaPending?.let { renderer ->
                dlnaPending = null
                playOnDlna(renderer, dlnaPendingOffer)
            }
            airplayPending?.let { device ->
                airplayPending = null
                playOnAirPlay(device)
            }
        } else {
            loadedUrl = null
            if (activeAirPlay != null) {
                activeAirPlay?.let { airplayMessages.remove(it.id) }
                activeAirPlay = null
            }
            if (activeDlna != null) {
                activeDlna?.let { dlnaMessages.remove(it.udn) }
                activeDlna = null
                activeDlnaOffer = null
            }
        }
        if (lastRunning != running) {
            lastRunning = running
            renderSpeakers()
        }
    }

    /** Carte verte et grand bouton : état de la diffusion, enceintes actives, niveau du son. */
    private fun renderHero(state: StreamState.Snapshot = StreamState.current) {
        val running = state.running
        val green = ContextCompat.getColor(this, R.color.green)
        val red = ContextCompat.getColor(this, R.color.red)

        // Grand bouton : vert pour démarrer, rouge pour arrêter.
        binding.toggleButton.setText(if (running) R.string.stop else R.string.start)
        binding.toggleButton.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        binding.toggleButton.backgroundTintList = ColorStateList.valueOf(if (running) red else green)

        val active = buildSpeakers().filter { it.state == Speaker.State.ACTIVE }
        val percent = (state.level * 100).toInt().coerceIn(0, 100)
        binding.heroChip.setText(if (running) R.string.hero_chip_running else R.string.hero_chip_idle)
        when {
            state.error != null -> {
                binding.heroTitle.setText(R.string.status_error)
                binding.heroDetail.text = state.error
            }
            !running -> {
                binding.heroTitle.setText(R.string.hero_idle_title)
                binding.heroDetail.setText(R.string.hero_idle_detail)
            }
            active.isEmpty() -> {
                binding.heroTitle.setText(R.string.hero_no_speaker)
                binding.heroDetail.text = if (state.baseUrl == null) {
                    getString(R.string.notification_no_wifi)
                } else {
                    getString(R.string.hero_no_speaker_detail)
                }
            }
            else -> {
                binding.heroTitle.text = active.joinToString(" + ") { it.name }
                val protocols = active.map {
                    when (it.kind) {
                        Speaker.Kind.CAST -> getString(R.string.speaker_cast)
                        Speaker.Kind.DLNA -> getString(R.string.speaker_dlna)
                        Speaker.Kind.AIRPLAY -> getString(R.string.speaker_airplay)
                    }
                }.distinct().joinToString(", ")
                val sound = getString(if (percent > 0) R.string.hero_sound_ok else R.string.hero_sound_none)
                binding.heroDetail.text = "$protocols · $sound"
            }
        }

        binding.levelGroup.visibility = if (running) View.VISIBLE else View.GONE
        binding.levelBar.progress = percent
        // Message d'aide seulement quand rien n'est capté.
        binding.levelText.visibility = if (running && percent == 0) View.VISIBLE else View.GONE
        binding.levelText.setText(if (prefs.muteLocal) R.string.level_silent_muted else R.string.level_silent)

        binding.resyncButton.visibility = if (running) View.VISIBLE else View.GONE
        binding.muteButton.setText(if (prefs.muteLocal) R.string.mute_on else R.string.mute_off)
        binding.muteButton.setIconResource(if (prefs.muteLocal) R.drawable.ic_mute else R.drawable.ic_volume)
        binding.muteButton.backgroundTintList = ColorStateList.valueOf(
            if (prefs.muteLocal) 0x33FFFFFF else android.graphics.Color.TRANSPARENT
        )

        val lastName = if (demo) DEMO_LAST_SPEAKER else prefs.lastSpeakerName
        binding.resumeButton.visibility = if (!running && lastName != null) View.VISIBLE else View.GONE
        if (lastName != null) binding.resumeButton.text = getString(R.string.resume_last, lastName)
    }

    private fun buildSpeakers(): List<Speaker> {
        if (demo) return demoSpeakers()
        val speakers = mutableListOf<Speaker>()
        val selectedId = mediaRouter?.selectedRoute?.id
        for (route in castRoutes()) {
            val selected = route.id == selectedId
            val state = when {
                !selected -> Speaker.State.IDLE
                castSession == null || castPlayerText == getString(R.string.cast_state_loading) ->
                    Speaker.State.CONNECTING
                else -> Speaker.State.ACTIVE
            }
            val detail = when {
                !selected -> getString(R.string.speaker_cast)
                castSession == null -> getString(R.string.speaker_connecting)
                else -> castPlayerText ?: getString(R.string.speaker_connected)
            }
            val volume = castSession?.takeIf { state == Speaker.State.ACTIVE }?.let { session ->
                runCatching { (session.volume * 100).toInt() }.getOrNull()
            }
            speakers += Speaker(CAST_PREFIX + route.id, route.name, Speaker.Kind.CAST, state, detail, volume)
        }
        // Un appareil qui propose l'AirPlay n'est pas répété en DLNA (ex. Freebox Player Delta,
        // dont le DLNA refuse la lecture), sauf s'il est justement en cours d'utilisation en DLNA.
        val airplayHosts = airplayDevices.map { it.host.hostAddress }.toSet()
        val airplayNames = airplayDevices.map { it.name.lowercase() }.toSet()
        for (renderer in dlnaRenderers) {
            val host = runCatching { URL(renderer.avTransportUrl).host }.getOrNull()
            val duplicate = host in airplayHosts || renderer.name.lowercase() in airplayNames
            if (duplicate && activeDlna?.udn != renderer.udn && dlnaConnecting != renderer.udn) continue
            val state = when (renderer.udn) {
                dlnaConnecting -> Speaker.State.CONNECTING
                activeDlna?.udn -> Speaker.State.ACTIVE
                else -> Speaker.State.IDLE
            }
            val detail = dlnaMessages[renderer.udn] ?: getString(R.string.speaker_dlna)
            speakers += Speaker(DLNA_PREFIX + renderer.udn, renderer.name, Speaker.Kind.DLNA, state, detail)
        }
        for (device in airplayDevices) {
            val state = when (device.id) {
                airplayConnecting -> Speaker.State.CONNECTING
                activeAirPlay?.id -> Speaker.State.ACTIVE
                else -> Speaker.State.IDLE
            }
            val detail = airplayMessages[device.id] ?: getString(R.string.speaker_airplay)
            val volume = if (state == Speaker.State.ACTIVE) prefs.airplayVolume else null
            speakers += Speaker(AIRPLAY_PREFIX + device.id, device.name, Speaker.Kind.AIRPLAY, state, detail, volume)
        }
        return speakers
    }

    private val speakerViews = mutableMapOf<String, ItemSpeakerBinding>()

    /** Met la liste à jour en réutilisant les vues existantes (pas de clignotement ni de clic perdu). */
    private fun renderSpeakers() {
        val speakers = buildSpeakers()
        val ids = speakers.map { it.id }
        if (ids != speakerViews.keys.toList()) {
            binding.speakerList.removeAllViews()
            speakerViews.clear()
            for (speaker in speakers) {
                val item = ItemSpeakerBinding.inflate(layoutInflater, binding.speakerList, false)
                binding.speakerList.addView(item.root)
                speakerViews[speaker.id] = item
            }
        }
        for (speaker in speakers) bindSpeaker(speakerViews.getValue(speaker.id), speaker)

        val searching = dlnaSearching && !demo
        binding.searchProgress.visibility = if (searching) View.VISIBLE else View.GONE
        binding.refreshButton.visibility = if (searching) View.GONE else View.VISIBLE
        binding.speakersEmpty.visibility = if (speakers.isEmpty()) View.VISIBLE else View.GONE
        binding.speakersEmpty.setText(if (searching) R.string.speakers_searching else R.string.speakers_none)
        renderHero()
    }

    /** Curseurs de volume en cours de manipulation : on ne les met pas à jour sous le doigt. */
    private val draggingVolume = mutableSetOf<String>()

    private fun bindSpeaker(item: ItemSpeakerBinding, speaker: Speaker) {
        item.speakerName.text = speaker.name
        item.speakerDetail.text = speaker.detail
        item.speakerIcon.setImageResource(
            when (speaker.kind) {
                Speaker.Kind.CAST -> R.drawable.ic_cast
                Speaker.Kind.DLNA -> R.drawable.ic_tv
                Speaker.Kind.AIRPLAY -> R.drawable.ic_airplay
            }
        )
        val green = ContextCompat.getColor(this, R.color.green)
        val active = speaker.state == Speaker.State.ACTIVE
        val highlighted = speaker.state != Speaker.State.IDLE

        // À droite : « Diffuser » (au repos), roue (connexion) ou pastille « En lecture ».
        item.speakerProgress.visibility = if (speaker.state == Speaker.State.CONNECTING) View.VISIBLE else View.GONE
        item.speakerChip.visibility = if (active) View.VISIBLE else View.GONE
        item.speakerAction.visibility = if (speaker.state == Speaker.State.IDLE) View.VISIBLE else View.GONE
        item.speakerAction.setOnClickListener { if (!demo) onSpeakerClicked(speaker) }

        // Icône dans un carré vert clair, ou vert plein pour l'enceinte en lecture.
        item.speakerIcon.backgroundTintList = ColorStateList.valueOf(
            if (active) green else ContextCompat.getColor(this, R.color.green_container)
        )
        item.speakerIcon.imageTintList = ColorStateList.valueOf(
            if (active) ContextCompat.getColor(this, R.color.on_green) else green
        )

        val card = item.speakerCard
        card.strokeColor = if (highlighted) green else ContextCompat.getColor(this, R.color.card_stroke)
        card.setCardBackgroundColor(
            ContextCompat.getColor(this, if (highlighted) R.color.green_container else R.color.card_bg)
        )

        // Volume de l'enceinte active.
        val volume = speaker.volume
        item.speakerVolumeGroup.visibility = if (volume != null) View.VISIBLE else View.GONE
        if (volume != null && speaker.id !in draggingVolume) {
            item.speakerVolume.value = volume.coerceIn(0, 100).toFloat()
        }
        item.speakerVolume.clearOnSliderTouchListeners()
        item.speakerVolume.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                draggingVolume += speaker.id
            }

            override fun onStopTrackingTouch(slider: Slider) {
                draggingVolume -= speaker.id
                val percent = slider.value.toInt()
                when (speaker.kind) {
                    Speaker.Kind.CAST -> setCastVolume(percent)
                    Speaker.Kind.AIRPLAY -> setAirPlayVolume(percent)
                    Speaker.Kind.DLNA -> {}
                }
            }
        })

        card.setOnClickListener {
            if (!demo) onSpeakerClicked(speaker)
        }
        card.setOnLongClickListener {
            if (demo || speaker.kind != Speaker.Kind.DLNA) return@setOnLongClickListener false
            dlnaRenderers.firstOrNull { DLNA_PREFIX + it.udn == speaker.id }?.let { chooseDlnaFormat(it) }
            true
        }
    }

    private fun onSpeakerClicked(speaker: Speaker) {
        when (speaker.kind) {
            Speaker.Kind.CAST -> castRoutes()
                .firstOrNull { CAST_PREFIX + it.id == speaker.id }
                ?.let { onCastSpeakerClicked(it) }
            Speaker.Kind.DLNA -> dlnaRenderers
                .firstOrNull { DLNA_PREFIX + it.udn == speaker.id }
                ?.let { onDlnaSpeakerClicked(it) }
            Speaker.Kind.AIRPLAY -> airplayDevices
                .firstOrNull { AIRPLAY_PREFIX + it.id == speaker.id }
                ?.let { onAirPlaySpeakerClicked(it) }
        }
    }

    private fun copyUrl() {
        val url = StreamState.current.streamUrl ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AudioCast", url))
        Toast.makeText(this, R.string.url_copied, Toast.LENGTH_SHORT).show()
    }

    /** Journal complet, précédé de quelques informations utiles au diagnostic. */
    private fun fullLog(): String {
        val header = "AudioCast ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · " +
            "${Build.MANUFACTURER} ${Build.MODEL}"
        return (listOf(header) + StreamState.current.log).joinToString("\n")
    }

    private fun copyLog() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.section_log), fullLog()))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Journal AudioCast")
            .putExtra(Intent.EXTRA_TEXT, fullLog())
        startActivity(Intent.createChooser(send, getString(R.string.log_share)))
    }

    // --- Démonstration (captures d'écran) ------------------------------------------------------

    private fun startDemo() {
        if (demoIdle) return
        StreamState.update {
            it.copy(running = true, baseUrl = "http://192.168.1.42:8765", clients = 1, level = 0.64f, error = null)
        }
        StreamState.log("AirPlay : lecture démarrée sur Freebox Player")
    }

    private fun demoSpeakers(): List<Speaker> {
        val playing = !demoIdle
        return listOf(
            Speaker(
                "demo-1", "Freebox Player", Speaker.Kind.AIRPLAY,
                if (playing) Speaker.State.ACTIVE else Speaker.State.IDLE,
                getString(R.string.speaker_airplay), if (playing) 55 else null,
            ),
            Speaker("demo-2", "Clé TV Mi", Speaker.Kind.CAST, Speaker.State.IDLE, getString(R.string.speaker_cast)),
            Speaker("demo-3", "Nest Mini cuisine", Speaker.Kind.CAST, Speaker.State.IDLE, getString(R.string.speaker_cast)),
        )
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val CAST_PREFIX = "cast:"
        private const val DLNA_PREFIX = "dlna:"
        private const val AIRPLAY_PREFIX = "airplay:"
        const val EXTRA_DEMO = "demo"
        const val EXTRA_DEMO_IDLE = "demo_idle"
        private const val DEMO_LAST_SPEAKER = "Freebox Player"
        private const val LOG_LINES_SHOWN = 40

        /** Lecteur DLNA en cours de lecture et format retenu (conservés si l'activité est recréée). */
        private var activeDlna: DlnaRenderer? = null
        private var activeDlnaOffer: DlnaOffer? = null

        /** Récepteur AirPlay en cours de lecture (la session vit dans [AirPlaySessions]). */
        private var activeAirPlay: AirPlayDevice? = null
    }
}
