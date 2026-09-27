package fr.cast.audio

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Formats de flux proposés par le serveur. */
enum class StreamFormat(val path: String, val mimeType: String) {
    /** AAC-LC en trames ADTS : léger, lu par Google Cast, VLC, les navigateurs. */
    AAC("/stream.aac", "audio/aac"),

    /** PCM 16 bits little-endian dans un conteneur WAV sans fin : Google Cast, la plupart des lecteurs. */
    WAV("/stream.wav", "audio/wav"),

    /** PCM 16 bits big-endian brut (LPCM) : format audio obligatoire de la norme DLNA. */
    L16("/stream.l16", "audio/L16;rate=48000;channels=2");

    companion object {
        fun fromPath(path: String) = when {
            path.endsWith(".wav") -> WAV
            path.endsWith(".l16") -> L16
            else -> AAC
        }
    }
}

/**
 * Mini serveur HTTP qui diffuse le son en continu à tous les clients connectés
 * (enceinte Google Cast, lecteur DLNA, VLC, navigateur…).
 */
class StreamServer(
    private val port: Int,
    private val onClientsChanged: (Int) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private class Client(val socket: Socket, val format: StreamFormat) {
        // Petite file : si un client prend du retard, on jette les vieilles trames pour limiter la latence.
        val queue = ArrayBlockingQueue<ByteArray>(QUEUE_SIZE)
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    fun start() {
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        serverSocket = socket
        running = true
        thread(name = "http-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "accept a échoué", e)
                    break
                }
                thread(name = "http-client", isDaemon = true) { handle(client) }
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        clients.forEach { closeQuietly(it.socket) }
        clients.clear()
        onClientsChanged(0)
    }

    fun hasClients(format: StreamFormat) = clients.any { it.format == format }

    /** Envoie un bloc (trame ADTS complète ou PCM) aux clients du format donné. */
    fun broadcast(format: StreamFormat, frame: ByteArray) {
        for (client in clients) {
            if (client.format != format) continue
            if (!client.queue.offer(frame)) {
                client.queue.clear()
                client.queue.offer(frame)
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return closeQuietly(socket)
            // Les en-têtes ne servent qu'au journal : c'est un flux en direct, sans reprise (Range).
            var userAgent = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("user-agent:", ignoreCase = true)) userAgent = line.substringAfter(':').trim()
            }
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val target = parts.getOrNull(1) ?: "/"
            val rawPath = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            // « /m/<type>/stream.xxx » : même flux, servi avec le type MIME exact attendu par le lecteur.
            var pathMime: String? = null
            var path = rawPath
            if (rawPath.startsWith("/m/")) {
                val rest = rawPath.removePrefix("/m/")
                pathMime = URLDecoder.decode(rest.substringBefore('/'), "UTF-8").takeIf { MIME_PATTERN.matches(it) }
                path = "/" + rest.substringAfter('/')
            }
            val out = socket.getOutputStream()
            if (path != "/favicon.ico") {
                val agent = userAgent.take(40).ifEmpty { "?" }
                onLog("$method $path ← ${socket.inetAddress.hostAddress} ($agent)")
            }

            when {
                method == "OPTIONS" -> {
                    writeHeaders(out, "204 No Content", null)
                    closeQuietly(socket)
                }
                path.startsWith("/stream") -> {
                    val format = StreamFormat.fromPath(path)
                    // Le lecteur DLNA peut attendre exactement le type qu'il a annoncé (ex. audio/x-wav).
                    val mimeType = pathMime ?: mimeOverride(query) ?: format.mimeType
                    writeHeaders(out, "200 OK", mimeType, dlna = true)
                    if (method == "HEAD") return closeQuietly(socket)
                    if (format == StreamFormat.WAV) {
                        out.write(wavHeader())
                        out.flush()
                    }
                    stream(socket, out, format)
                }
                path == "/" -> {
                    val body = INDEX_HTML.toByteArray(Charsets.UTF_8)
                    writeHeaders(out, "200 OK", "text/html; charset=utf-8", body.size)
                    if (method != "HEAD") out.write(body)
                    out.flush()
                    closeQuietly(socket)
                }
                else -> {
                    writeHeaders(out, "404 Not Found", "text/plain", 0)
                    closeQuietly(socket)
                }
            }
        } catch (e: IOException) {
            closeQuietly(socket)
        }
    }

    private fun stream(socket: Socket, out: OutputStream, format: StreamFormat) {
        val client = Client(socket, format)
        clients.add(client)
        onClientsChanged(clients.size)
        val startMs = System.currentTimeMillis()
        try {
            while (running) {
                val frame = client.queue.poll(1, TimeUnit.SECONDS) ?: continue
                out.write(frame)
                // Regroupe les trames déjà disponibles avant de vider le tampon.
                while (true) {
                    val next = client.queue.poll() ?: break
                    out.write(next)
                }
                out.flush()
            }
        } catch (_: SocketException) {
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            clients.remove(client)
            closeQuietly(socket)
            onClientsChanged(clients.size)
            val seconds = (System.currentTimeMillis() - startMs) / 1000
            onLog("Fin du flux ${format.name} → ${socket.inetAddress.hostAddress} après $seconds s")
        }
    }

    private fun writeHeaders(
        out: OutputStream,
        status: String,
        contentType: String?,
        length: Int? = null,
        dlna: Boolean = false,
    ) {
        val sb = StringBuilder()
            .append("HTTP/1.1 ").append(status).append("\r\n")
            .append("Server: AudioCast\r\n")
            .append("Cache-Control: no-cache, no-store\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
            .append("Access-Control-Allow-Headers: *\r\n")
            .append("Connection: close\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        if (length != null) sb.append("Content-Length: ").append(length).append("\r\n")
        if (dlna) {
            // En-têtes attendus par de nombreux lecteurs DLNA (flux en direct, non navigable).
            sb.append("transferMode.dlna.org: Streaming\r\n")
            sb.append("contentFeatures.dlna.org: ").append(DLNA_FEATURES).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun mimeOverride(query: String): String? {
        val value = query.split('&')
            .firstOrNull { it.startsWith("mime=") }
            ?.substringAfter('=')
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?: return null
        return value.takeIf { MIME_PATTERN.matches(it) }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    /**
     * En-tête WAV d'un flux PCM 16 bits sans fin. Les tailles sont finies (≈ 2 Go, plus de 3 h
     * d'audio) : de nombreux lecteurs Android refusent la valeur « inconnue » 0xFFFFFFFF.
     */
    private fun wavHeader(): ByteArray {
        val sampleRate = AudioCaptureService.SAMPLE_RATE
        val channels = AudioCaptureService.CHANNELS
        val byteRate = sampleRate * channels * 2
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(WAV_DATA_SIZE + 36)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 2).toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(WAV_DATA_SIZE)
        }.array()
    }

    companion object {
        private const val TAG = "StreamServer"

        /** Taille annoncée des données WAV : la plus grande valeur signée, multiple de 4 octets. */
        private const val WAV_DATA_SIZE = (Int.MAX_VALUE - 44) and 3.inv()
        const val DLNA_FEATURES = "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        // ≈ 2 s d'audio : au-delà, l'enceinte a pris du retard et on jette le plus ancien
        // pour que le décalage ne s'accumule pas.
        private const val QUEUE_SIZE = 100
        private val MIME_PATTERN = Regex("audio/[A-Za-z0-9.+-]+(;[A-Za-z0-9=.;-]+)?")

        private const val INDEX_HTML = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AudioCast</title></head>
<body style="font-family:sans-serif;text-align:center;padding:2em">
<h1>AudioCast</h1>
<p>Son du téléphone en direct</p>
<audio src="/stream.aac" controls autoplay></audio>
<p>Flux : <code>/stream.aac</code> (AAC) ou <code>/stream.wav</code> (WAV) — utilisables dans VLC, Kodi…</p>
</body></html>"""
    }
}
