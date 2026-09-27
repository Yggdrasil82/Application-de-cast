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
import android.view.View
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
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fr.cast.audio.databinding.ActivityMainBinding
import fr.cast.audio.databinding.ItemSpeakerBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    /** Une enceinte de la liste, quel que soit son protocole. */
    private data class Speaker(
        val id: String,
        val name: String,
        val kind: Kind,
        val state: State,
        val detail: String,
    ) {
        enum class Kind { CAST, DLNA }
        enum class State { IDLE, CONNECTING, ACTIVE }
    }

    private lateinit var binding: ActivityMainBinding
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

    /** Mode démonstration (builds debug) : données fictives pour les captures d'écran. */
    private var demo = false

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
        binding.refreshButton.setOnClickListener { searchDlna() }
        binding.copyUrlButton.setOnClickListener { copyUrl() }
        binding.copyLogButton.setOnClickListener { copyLog() }
        binding.shareLogButton.setOnClickListener { shareLog() }
        binding.advancedToggle.setOnClickListener {
            val show = binding.advancedGroup.visibility != View.VISIBLE
            binding.advancedGroup.visibility = if (show) View.VISIBLE else View.GONE
            binding.advancedToggle.setIconResource(if (show) R.drawable.ic_collapse else R.drawable.ic_expand)
        }

        demo = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEMO, false)
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
        renderSpeakers()
    }

    override fun onStop() {
        castSession?.remoteMediaClient?.unregisterCallback(remoteCallback)
        mediaRouter?.removeCallback(routerCallback)
        StreamState.removeListener(stateListener)
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

    /** Arrête tout : enceintes Cast et DLNA, puis la capture. */
    private fun stopCasting() {
        castSession?.remoteMediaClient?.stop()
        loadedUrl = null
        stopDlna()
        dlnaPending = null
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
    }

    // --- Interface -----------------------------------------------------------------------------

    private var lastRunning: Boolean? = null

    private fun render(state: StreamState.Snapshot) {
        val running = state.running
        binding.toggleButton.setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        binding.toggleButton.contentDescription = getString(if (running) R.string.stop else R.string.start)
        binding.heroCard.setCardBackgroundColor(
            MaterialColors.getColor(
                binding.heroCard,
                if (running) com.google.android.material.R.attr.colorPrimaryContainer
                else com.google.android.material.R.attr.colorSurfaceContainerHighest,
            )
        )
        binding.statusTitle.setText(
            when {
                state.error != null -> R.string.status_error
                running -> R.string.status_running
                else -> R.string.status_idle
            }
        )
        binding.statusDetail.text = when {
            state.error != null -> state.error
            !running -> getString(R.string.status_idle_detail)
            state.baseUrl == null -> getString(R.string.notification_no_wifi)
            else -> resources.getQuantityString(R.plurals.status_running_detail, state.clients, state.clients)
        }

        val percent = (state.level * 100).toInt().coerceIn(0, 100)
        binding.levelGroup.visibility = if (running) View.VISIBLE else View.GONE
        binding.levelText.visibility = if (running) View.VISIBLE else View.GONE
        binding.levelBar.progress = percent
        binding.levelText.setText(if (percent > 0) R.string.level_ok else R.string.level_silent)
        binding.resyncButton.visibility = if (running) View.VISIBLE else View.GONE

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
        } else {
            loadedUrl = null
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
            speakers += Speaker(CAST_PREFIX + route.id, route.name, Speaker.Kind.CAST, state, detail)
        }
        for (renderer in dlnaRenderers) {
            val state = when (renderer.udn) {
                dlnaConnecting -> Speaker.State.CONNECTING
                activeDlna?.udn -> Speaker.State.ACTIVE
                else -> Speaker.State.IDLE
            }
            val detail = dlnaMessages[renderer.udn] ?: getString(R.string.speaker_dlna)
            speakers += Speaker(DLNA_PREFIX + renderer.udn, renderer.name, Speaker.Kind.DLNA, state, detail)
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
    }

    private fun bindSpeaker(item: ItemSpeakerBinding, speaker: Speaker) {
        item.speakerName.text = speaker.name
        item.speakerDetail.text = speaker.detail
        item.speakerIcon.setImageResource(
            if (speaker.kind == Speaker.Kind.CAST) R.drawable.ic_cast else R.drawable.ic_tv
        )
        item.speakerProgress.visibility = if (speaker.state == Speaker.State.CONNECTING) View.VISIBLE else View.GONE
        item.speakerPlaying.visibility = if (speaker.state == Speaker.State.ACTIVE) View.VISIBLE else View.GONE

        val highlighted = speaker.state != Speaker.State.IDLE
        val card = item.speakerCard
        card.strokeWidth = resources.getDimensionPixelSize(if (highlighted) R.dimen.stroke_active else R.dimen.stroke_idle)
        card.strokeColor = MaterialColors.getColor(
            card,
            if (highlighted) com.google.android.material.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOutlineVariant,
        )
        card.setCardBackgroundColor(
            ColorStateList.valueOf(
                MaterialColors.getColor(
                    card,
                    if (highlighted) com.google.android.material.R.attr.colorPrimaryContainer
                    else com.google.android.material.R.attr.colorSurface,
                )
            )
        )

        card.setOnClickListener {
            if (demo) return@setOnClickListener
            when (speaker.kind) {
                Speaker.Kind.CAST -> castRoutes()
                    .firstOrNull { CAST_PREFIX + it.id == speaker.id }
                    ?.let { onCastSpeakerClicked(it) }
                Speaker.Kind.DLNA -> dlnaRenderers
                    .firstOrNull { DLNA_PREFIX + it.udn == speaker.id }
                    ?.let { onDlnaSpeakerClicked(it) }
            }
        }
        card.setOnLongClickListener {
            if (demo || speaker.kind != Speaker.Kind.DLNA) return@setOnLongClickListener false
            dlnaRenderers.firstOrNull { DLNA_PREFIX + it.udn == speaker.id }?.let { chooseDlnaFormat(it) }
            true
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
        StreamState.update {
            it.copy(running = true, baseUrl = "http://192.168.1.42:8765", clients = 1, level = 0.62f, error = null)
        }
        StreamState.log("GET /stream.wav ← 192.168.1.30 (CrKey/1.56)")
        StreamState.log("Google Cast : envoi du flux WAV à Salon")
    }

    private fun demoSpeakers() = listOf(
        Speaker("demo-1", "Salon", Speaker.Kind.CAST, Speaker.State.ACTIVE, getString(R.string.cast_state_playing, "WAV")),
        Speaker("demo-2", "Cuisine", Speaker.Kind.CAST, Speaker.State.IDLE, getString(R.string.speaker_cast)),
        Speaker("demo-3", "Freebox Player", Speaker.Kind.DLNA, Speaker.State.IDLE, getString(R.string.speaker_dlna)),
    )

    companion object {
        private const val TAG = "MainActivity"
        private const val CAST_PREFIX = "cast:"
        private const val DLNA_PREFIX = "dlna:"
        const val EXTRA_DEMO = "demo"
        private const val LOG_LINES_SHOWN = 40

        /** Lecteur DLNA en cours de lecture et format retenu (conservés si l'activité est recréée). */
        private var activeDlna: DlnaRenderer? = null
        private var activeDlnaOffer: DlnaOffer? = null
    }
}
