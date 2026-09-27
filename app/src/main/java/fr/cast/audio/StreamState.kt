package fr.cast.audio

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/** État partagé entre le service de capture et l'interface. Les écouteurs sont appelés sur le thread principal. */
object StreamState {
    data class Snapshot(
        val running: Boolean = false,
        /** Adresse du serveur local, ex. http://192.168.1.20:8765 (null sans Wi-Fi). */
        val baseUrl: String? = null,
        val clients: Int = 0,
        val error: String? = null,
        /** Niveau crête du son capté (0 à 1), rafraîchi plusieurs fois par seconde. */
        val level: Float = 0f,
        /** Dernières lignes du journal (requêtes reçues, échanges DLNA…), la plus récente en dernier. */
        val log: List<String> = emptyList(),
    ) {
        /** Flux AAC : Google Cast, VLC, navigateurs. */
        val streamUrl: String? get() = baseUrl?.let { it + StreamFormat.AAC.path }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()

    @Volatile
    var current = Snapshot()
        private set

    fun update(transform: (Snapshot) -> Snapshot) {
        synchronized(this) { current = transform(current) }
        val snapshot = current
        mainHandler.post { listeners.forEach { it(snapshot) } }
    }

    /** Ajoute une ligne horodatée au journal affiché dans l'application. */
    fun log(message: String) {
        Log.i("AudioCast", message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())
        update { it.copy(log = (it.log + "$time  $message").takeLast(MAX_LOG_LINES)) }
    }

    private const val MAX_LOG_LINES = 12

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }
}
