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
import java.net.URLEncoder

/** Lecteur DLNA/UPnP (MediaRenderer) découvert sur le réseau : Sonos, Freebox, TV, amplis… */
data class DlnaRenderer(
    val udn: String,
    val name: String,
    val avTransportUrl: String,
    val avTransportType: String,
    val connectionManagerUrl: String?,
    val connectionManagerType: String?,
)

/** Manière de servir le flux à un lecteur : format produit et type MIME annoncé au lecteur. */
data class DlnaOffer(val format: StreamFormat, val mimeType: String)

/**
 * Client DLNA minimal : découverte SSDP et pilotage AVTransport par SOAP.
 * Toutes les méthodes sont bloquantes : à appeler hors du thread principal.
 */
object Dlna {
    private const val TAG = "Dlna"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val HTTP_TIMEOUT_MS = 4000
    private const val PLAY_CHECK_SECONDS = 12

    private val WAV_MIMES = setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
    private val AAC_MIMES = setOf("audio/aac", "audio/x-aac", "audio/aacp", "audio/vnd.dlna.adts")
    private val AUDIO_HINTS = listOf("l16", "wav", "wave", "aac", "adts")

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
     * Façons de servir le flux à ce lecteur, de la plus probable à la moins probable :
     * d'abord les formats qu'il annonce (GetProtocolInfo), avec le type MIME exact qu'il attend,
     * puis les autres en dernier recours.
     */
    fun offers(renderer: DlnaRenderer, log: (String) -> Unit = {}): List<DlnaOffer> {
        val mimes = sinkMimeTypes(renderer)
        if (mimes.isEmpty()) {
            log("${renderer.name} n'indique pas les formats qu'il accepte")
        } else {
            val interesting = mimes.filter { m -> AUDIO_HINTS.any { it in m.lowercase() } }.distinct()
            log("${renderer.name} accepte : ${interesting.joinToString(", ").ifEmpty { "aucun format utilisable" }}")
        }
        val lower = mimes.map { it.lowercase() }
        val offers = mutableListOf<DlnaOffer>()
        if (lower.any { it.startsWith("audio/l16") && ("rate=" !in it || "rate=48000" in it) }) {
            offers += DlnaOffer(StreamFormat.L16, StreamFormat.L16.mimeType)
        }
        mimes.firstOrNull { it.lowercase() in WAV_MIMES }?.let { offers += DlnaOffer(StreamFormat.WAV, it) }
        mimes.firstOrNull { it.lowercase() in AAC_MIMES }?.let { offers += DlnaOffer(StreamFormat.AAC, it) }
        for (format in listOf(StreamFormat.WAV, StreamFormat.AAC, StreamFormat.L16)) {
            if (offers.none { it.format == format }) offers += DlnaOffer(format, format.mimeType)
        }
        return offers
    }

    /**
     * Essaie les [offers] une par une jusqu'à ce que le lecteur lise vraiment le flux :
     * il doit être en état PLAYING ET être connecté au serveur du téléphone ([isStreaming]).
     * Renvoie l'offre retenue, ou null si aucune ne marche.
     */
    fun play(
        renderer: DlnaRenderer,
        baseUrl: String,
        title: String,
        offers: List<DlnaOffer>,
        isStreaming: (StreamFormat) -> Boolean,
        log: (String) -> Unit,
    ): DlnaOffer? {
        for (offer in offers) {
            val url = baseUrl + offer.format.path + "?mime=" + URLEncoder.encode(offer.mimeType, "UTF-8")
            log("Essai en ${offer.format.name} (${offer.mimeType})")
            try {
                try {
                    stop(renderer) // certains lecteurs refusent un nouveau flux pendant une lecture
                } catch (_: IOException) {
                }
                soap(
                    renderer.avTransportUrl, renderer.avTransportType, "SetAVTransportURI",
                    "InstanceID" to "0",
                    "CurrentURI" to url,
                    "CurrentURIMetaData" to didl(url, offer, title),
                )
                playWithRetry(renderer)
            } catch (e: IOException) {
                log("Refusé : ${e.message}")
                continue
            }
            if (waitUntilPlaying(renderer, offer.format, isStreaming, log)) {
                log("${renderer.name} lit le flux en ${offer.format.name}")
                return offer
            }
            log("Pas de lecture en ${offer.format.name}, format suivant…")
        }
        return null
    }

    fun stop(renderer: DlnaRenderer) {
        soap(renderer.avTransportUrl, renderer.avTransportType, "Stop", "InstanceID" to "0")
    }

    /** Juste après SetAVTransportURI, certains lecteurs sont encore en transition (erreur 701). */
    private fun playWithRetry(renderer: DlnaRenderer) {
        var attempt = 0
        while (true) {
            try {
                soap(renderer.avTransportUrl, renderer.avTransportType, "Play", "InstanceID" to "0", "Speed" to "1")
                return
            } catch (e: IOException) {
                if (++attempt >= 3) throw e
                Thread.sleep(700)
            }
        }
    }

    private fun waitUntilPlaying(
        renderer: DlnaRenderer,
        format: StreamFormat,
        isStreaming: (StreamFormat) -> Boolean,
        log: (String) -> Unit,
    ): Boolean {
        var lastState: String? = null
        var good = 0
        for (second in 1..PLAY_CHECK_SECONDS) {
            Thread.sleep(1000)
            val state = try {
                transportState(renderer)
            } catch (_: IOException) {
                null
            }
            val streaming = isStreaming(format)
            if (state != null && state != lastState) {
                log("État du lecteur : $state")
                lastState = state
            }
            // Sans réponse à GetTransportInfo, on se contente de la connexion au flux.
            good = if (streaming && (state == null || state == "PLAYING")) good + 1 else 0
            if (good >= 3) return true
            if (second >= 5 && !streaming && state in listOf("STOPPED", "NO_MEDIA_PRESENT")) return false
        }
        return false
    }

    private fun transportState(renderer: DlnaRenderer): String? {
        val response = soap(renderer.avTransportUrl, renderer.avTransportType, "GetTransportInfo", "InstanceID" to "0")
        return Regex("<CurrentTransportState>(.*?)</CurrentTransportState>").find(response)?.groupValues?.get(1)
    }

    /** Types MIME annoncés par le lecteur (3e champ de chaque protocolInfo « http-get:*:mime:… »). */
    private fun sinkMimeTypes(renderer: DlnaRenderer): List<String> {
        val cmUrl = renderer.connectionManagerUrl ?: return emptyList()
        val cmType = renderer.connectionManagerType ?: return emptyList()
        val sink = try {
            val response = soap(cmUrl, cmType, "GetProtocolInfo")
            Regex("<Sink>(.*?)</Sink>", RegexOption.DOT_MATCHES_ALL).find(response)?.groupValues?.get(1)
        } catch (e: IOException) {
            Log.w(TAG, "GetProtocolInfo a échoué", e)
            null
        } ?: return emptyList()
        return unescape(sink).split(',')
            .map { it.trim().split(':') }
            .filter { it.size >= 3 && it[0].equals("http-get", ignoreCase = true) }
            .map { it[2] }
    }

    private fun didl(url: String, offer: DlnaOffer, title: String): String {
        val profile = if (offer.format == StreamFormat.L16) "DLNA.ORG_PN=LPCM;" else ""
        val protocolInfo = "http-get:*:${offer.mimeType}:$profile${StreamServer.DLNA_FEATURES}"
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"audiocast\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${escape(title)}</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
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

    private fun unescape(text: String) = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun escape(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
