package fr.cast.audio

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** État partagé entre le service de capture et l'interface. Les écouteurs sont appelés sur le thread principal. */
object StreamState {
    data class Snapshot(
        val running: Boolean = false,
        val streamUrl: String? = null,
        val clients: Int = 0,
        val error: String? = null,
    )

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

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }
}
