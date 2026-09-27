package fr.cast.audio

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL

/** Lecteur DLNA/UPnP (MediaRenderer) découvert sur le réseau : Sonos, Freebox, TV, amplis… */
data class DlnaRenderer(
    val udn: String,
    val name: String,
    val avTransportUrl: String,
    val avTransportType: String,
    val connectionManagerUrl: String?,
    val connectionManagerType: String?,
)

/**
 * Client DLNA minimal : découverte SSDP et pilotage AVTransport par SOAP.
 * Toutes les méthodes sont bloquantes : à appeler hors du thread principal.
 */
object Dlna {
    private const val TAG = "Dlna"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val HTTP_TIMEOUT_MS = 4000

    private val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
    )

    // --- Découverte ----------------------------------------------------------------------------

    fun discover(context: Context, timeoutMs: Long = 4000): List<DlnaRenderer> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("AudioCast:ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            val locations = searchLocations(context, timeoutMs)
            return locations.mapNotNull { location ->
                try {
                    describe(location)
                } catch (e: Exception) {
                    Log.w(TAG, "Description illisible : $location", e)
                    null
                }
            }.distinctBy { it.udn }
        } finally {
            lock.release()
        }
    }

    private fun searchLocations(context: Context, timeoutMs: Long): Set<String> {
        val locations = LinkedHashSet<String>()
        // On se lie à l'adresse Wi-Fi pour que la requête multicast parte bien sur le réseau local.
        val localAddress = NetworkUtils.localIpv4(context)?.let { InetAddress.getByName(it) }
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.soTimeout = 300
            socket.bind(InetSocketAddress(localAddress, 0))
            val group = InetAddress.getByName(SSDP_ADDRESS)
            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            var sends = 0
            var nextSend = 0L
            while (System.currentTimeMillis() < deadline) {
                if (sends < 3 && System.currentTimeMillis() >= nextSend) {
                    for (target in SEARCH_TARGETS) {
                        val msg = "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: $target\r\n\r\n"
                        val bytes = msg.toByteArray(Charsets.US_ASCII)
                        socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
                    }
                    sends++
                    nextSend = System.currentTimeMillis() + 1000
                }
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    response.lineSequence()
                        .firstOrNull { it.startsWith("location:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()
                        ?.let { locations += it }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return locations
    }

    private class Service(var type: String = "", var controlUrl: String = "")
    private class Device(
        var type: String = "",
        var name: String = "",
        var udn: String = "",
        val services: MutableList<Service> = mutableListOf(),
    )

    /** Lit la description XML d'un appareil et extrait son lecteur (device qui porte AVTransport). */
    private fun describe(location: String): DlnaRenderer? {
        val xml = httpGet(location)
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())

        var urlBase: String? = null
        val stack = mutableListOf<Device>()
        val devices = mutableListOf<Device>()
        var service: Service? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "device" -> stack += Device()
                    "service" -> service = Service()
                    "URLBase" -> urlBase = parser.nextText().trim()
                    "serviceType" -> service?.type = parser.nextText().trim()
                    "controlURL" -> service?.controlUrl = parser.nextText().trim()
                    "deviceType" -> if (service == null) stack.lastOrNull()?.type = parser.nextText().trim()
                    "friendlyName" -> if (service == null) stack.lastOrNull()?.name = parser.nextText().trim()
                    "UDN" -> if (service == null) stack.lastOrNull()?.udn = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "service" -> {
                        service?.let { stack.lastOrNull()?.services?.add(it) }
                        service = null
                    }
                    "device" -> if (stack.isNotEmpty()) devices += stack.removeAt(stack.lastIndex)
                }
            }
            event = parser.next()
        }

        // Les appareils imbriqués sont ajoutés avant leur parent : le dernier est la racine.
        val root = devices.lastOrNull() ?: return null
        val renderer = devices.firstOrNull { d -> d.services.any { it.type.contains(":AVTransport:") } }
            ?: return null
        val avTransport = renderer.services.first { it.type.contains(":AVTransport:") }
        val connectionManager = renderer.services.firstOrNull { it.type.contains(":ConnectionManager:") }
        val base = URL(urlBase?.takeIf { it.isNotEmpty() } ?: location)

        return DlnaRenderer(
            udn = renderer.udn.ifEmpty { location },
            name = renderer.name.ifEmpty { root.name }.ifEmpty { URL(location).host },
            avTransportUrl = URL(base, avTransport.controlUrl).toString(),
            avTransportType = avTransport.type,
            connectionManagerUrl = connectionManager?.let { URL(base, it.controlUrl).toString() },
            connectionManagerType = connectionManager?.type,
        )
    }

    // --- Lecture -------------------------------------------------------------------------------

    /**
     * Demande au lecteur de lire le flux du téléphone. Choisit WAV ou AAC selon ce que l'appareil
     * annonce savoir lire, et renvoie le format retenu.
     */
    fun play(renderer: DlnaRenderer, baseUrl: String, title: String): StreamFormat {
        val format = chooseFormat(renderer)
        val url = baseUrl + format.path
        try {
            stop(renderer) // certains lecteurs refusent un nouveau flux pendant une lecture
        } catch (_: IOException) {
        }
        soap(
            renderer.avTransportUrl, renderer.avTransportType, "SetAVTransportURI",
            "InstanceID" to "0",
            "CurrentURI" to url,
            "CurrentURIMetaData" to didl(url, format, title),
        )
        soap(renderer.avTransportUrl, renderer.avTransportType, "Play", "InstanceID" to "0", "Speed" to "1")
        return format
    }

    fun stop(renderer: DlnaRenderer) {
        soap(renderer.avTransportUrl, renderer.avTransportType, "Stop", "InstanceID" to "0")
    }

    private fun chooseFormat(renderer: DlnaRenderer): StreamFormat {
        val sink = try {
            if (renderer.connectionManagerUrl != null && renderer.connectionManagerType != null) {
                val response = soap(renderer.connectionManagerUrl, renderer.connectionManagerType, "GetProtocolInfo")
                Regex("<Sink>(.*?)</Sink>", RegexOption.DOT_MATCHES_ALL).find(response)?.groupValues?.get(1)
            } else {
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "GetProtocolInfo a échoué", e)
            null
        }?.lowercase().orEmpty()

        return when {
            listOf("audio/wav", "audio/x-wav", "audio/wave").any { it in sink } -> StreamFormat.WAV
            listOf("audio/aac", "audio/x-aac", "adts", "audio/mp4").any { it in sink } -> StreamFormat.AAC
            else -> StreamFormat.WAV // LPCM est le seul format audio obligatoire en DLNA
        }
    }

    private fun didl(url: String, format: StreamFormat, title: String): String {
        val protocolInfo = "http-get:*:${format.mimeType}:${StreamServer.DLNA_FEATURES}"
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"audiocast\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${escape(title)}</dc:title>" +
            "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>" +
            "<res protocolInfo=\"${escape(protocolInfo)}\">${escape(url)}</res>" +
            "</item></DIDL-Lite>"
    }

    // --- HTTP / SOAP ---------------------------------------------------------------------------

    private fun soap(controlUrl: String, serviceType: String, action: String, vararg args: Pair<String, String>): String {
        val arguments = args.joinToString("") { (name, value) -> "<$name>${escape(value)}</$name>" }
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$serviceType\">$arguments</u:$action></s:Body></s:Envelope>"
        val bytes = body.toByteArray(Charsets.UTF_8)

        val conn = URL(controlUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val upnpError = Regex("<errorDescription>(.*?)</errorDescription>").find(error)?.groupValues?.get(1)
                throw IOException("$action : HTTP $code${upnpError?.let { " ($it)" }.orEmpty()}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} : $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun escape(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
