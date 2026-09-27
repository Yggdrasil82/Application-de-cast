package fr.cast.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetAddress
import kotlin.concurrent.thread

/** Récepteur AirPlay (AirMedia chez Free) annoncé sur le réseau par le service mDNS « _raop._tcp ». */
data class AirPlayDevice(
    /** Nom du service mDNS, unique sur le réseau (ex. « 0024D4XXXXXX@Freebox Player »). */
    val id: String,
    val name: String,
    val host: InetAddress,
    val port: Int,
    /** Enregistrement TXT : et (chiffrement), cn (codecs), pw (mot de passe), am (modèle)… */
    val txt: Map<String, String>,
) {
    fun describe() = "$name (${host.hostAddress}:$port) " +
        txt.entries.joinToString(" ") { "${it.key}=${it.value}" }
}

/**
 * Découverte des récepteurs AirPlay via le service de découverte d'Android (NsdManager).
 * Les rappels [onChange] sont faits sur le thread principal.
 */
class AirPlayDiscovery(context: Context, private val onChange: (List<AirPlayDevice>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val devices = LinkedHashMap<String, AirPlayDevice>()
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    val current: List<AirPlayDevice> get() = devices.values.toList()

    fun start() {
        if (listener != null) return
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { enqueue(info) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (devices.remove(info.serviceName) != null) publish()
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Découverte AirPlay impossible ($errorCode)")
                main.post { if (listener === this) listener = null }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
        }
        listener = discovery
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Découverte AirPlay impossible", e)
            listener = null
        }
    }

    fun stop() {
        val discovery = listener ?: return
        listener = null
        try {
            nsd.stopServiceDiscovery(discovery)
        } catch (_: RuntimeException) {
        }
    }

    private fun enqueue(info: NsdServiceInfo) {
        if (toResolve.none { it.serviceName == info.serviceName }) toResolve.addLast(info)
        resolveNext()
    }

    /** Avant Android 14, une seule résolution à la fois est possible : on les enchaîne. */
    private fun resolveNext() {
        if (resolving) return
        val info = toResolve.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    resolving = false
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                main.post {
                    add(serviceInfo)
                    resolving = false
                    resolveNext()
                }
            }
        })
    }

    private fun add(info: NsdServiceInfo) {
        @Suppress("DEPRECATION")
        val host = info.host ?: return
        val txt = info.attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8).orEmpty() }
        val name = info.serviceName.substringAfter('@').ifEmpty { info.serviceName }
        devices[info.serviceName] = AirPlayDevice(info.serviceName, name, host, info.port, txt)
        publish()
    }

    private fun publish() = onChange(current)

    companion object {
        private const val TAG = "AirPlayDiscovery"
        private const val SERVICE_TYPE = "_raop._tcp"
    }
}

/** Session AirPlay en cours (une à la fois), alimentée par le fil de capture du service. */
object AirPlaySessions {
    @Volatile
    var active: RaopSession? = null
        private set

    /** Ouvre une session (bloquant : à appeler hors du thread principal). */
    fun start(device: AirPlayDevice, log: (String) -> Unit) {
        stop()
        val session = RaopSession(device, log)
        session.start()
        active = session
        AudioCaptureService.pcmSinks.add(session.sink)
    }

    fun stop() {
        val session = active ?: return
        active = null
        AudioCaptureService.pcmSinks.remove(session.sink)
        thread(name = "raop-stop", isDaemon = true) { session.stop() }
    }
}
