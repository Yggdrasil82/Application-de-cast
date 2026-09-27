package fr.cast.audio

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Émetteur AirPlay 1 (RAOP) : négociation RTSP, puis son en ALAC non compressé (sans perte)
 * dans des paquets RTP en UDP, chiffrés en AES si le récepteur l'exige.
 *
 * Le PCM attendu est en 16 bits stéréo little-endian à 44,1 kHz.
 */
class RaopSession(
    private val device: AirPlayDevice,
    private val log: (String) -> Unit,
    private val password: String? = null,
    /** Volume de départ, de 0 à 100. */
    initialVolume: Int = DEFAULT_VOLUME,
) {
    /** Authentification HTTP Digest (royaume, nonce) une fois le défi du récepteur reçu. */
    private var digest: Pair<String, String>? = null

    @Volatile
    private var volume = initialVolume

    private val random = SecureRandom()
    private val audioSocket = DatagramSocket()
    private val controlSocket = DatagramSocket()
    private val timingSocket = DatagramSocket()
    private var rtsp: Socket? = null
    private var url = ""
    private var cseq = 0
    private var session: String? = null
    private val sessionId = (random.nextInt() and Int.MAX_VALUE).toString()
    private val clientInstance = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02X".format(it) }

    private var serverPort = 0
    private var serverControlPort = 0

    private val ssrc = random.nextInt()
    private var seq = random.nextInt(0x10000)

    @Volatile
    private var rtpTime = random.nextInt().toLong() and 0xFFFFFFFFL
    private var firstPacket = true

    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null
    private val aes: Cipher by lazy { Cipher.getInstance("AES/CBC/NoPadding") }

    /** Derniers paquets envoyés, pour les renvoyer si le récepteur en a perdu. */
    private val history = arrayOfNulls<ByteArray>(HISTORY_SIZE)

    private val pending = ByteArray(FRAMES_PER_PACKET * BYTES_PER_FRAME)
    private var pendingLength = 0

    @Volatile
    private var running = false

    /** À enregistrer auprès du service de capture. */
    val sink: (ByteArray, Int) -> Unit = { pcm, length -> feed(pcm, length) }

    /** Négociation complète avec le récepteur (bloquant). */
    fun start() {
        log("AirPlay : ${device.describe()}")
        val encryptionTypes = device.txt["et"]?.split(',')?.map { it.trim() }.orEmpty()
        // « 1 » = chiffrement RSA/AES. Sans « 0 » dans la liste, le récepteur l'impose.
        val encrypt = "1" in encryptionTypes && "0" !in encryptionTypes
        if (encryptionTypes.isNotEmpty() && "0" !in encryptionTypes && "1" !in encryptionTypes) {
            log("AirPlay : chiffrement demandé non géré (et=${device.txt["et"]}), tentative quand même")
        }

        val socket = Socket()
        socket.connect(InetSocketAddress(device.host, device.port), TIMEOUT_MS)
        socket.soTimeout = TIMEOUT_MS
        socket.tcpNoDelay = true
        rtsp = socket
        val local = socket.localAddress.hostAddress
        url = "rtsp://$local/$sessionId"

        startUdpThreads()

        val challenge = ByteArray(16).also { random.nextBytes(it) }
        request("OPTIONS", "*", mapOf("Apple-Challenge" to base64(challenge)))

        val sdp = StringBuilder()
            .append("v=0\r\n")
            .append("o=iTunes $sessionId 0 IN IP4 $local\r\n")
            .append("s=iTunes\r\n")
            .append("c=IN IP4 ${device.host.hostAddress}\r\n")
            .append("t=0 0\r\n")
            .append("m=audio 0 RTP/AVP 96\r\n")
            .append("a=rtpmap:96 AppleLossless\r\n")
            .append("a=fmtp:96 $FRAMES_PER_PACKET 0 16 40 10 14 2 255 0 0 ${AudioCaptureService.SAMPLE_RATE}\r\n")
        if (encrypt) {
            val key = ByteArray(16).also { random.nextBytes(it) }
            val iv = ByteArray(16).also { random.nextBytes(it) }
            aesKey = key
            aesIv = iv
            sdp.append("a=rsaaeskey:${base64(rsaEncrypt(key))}\r\n")
            sdp.append("a=aesiv:${base64(iv)}\r\n")
        }
        request("ANNOUNCE", url, mapOf("Content-Type" to "application/sdp"), sdp.toString())

        val transport = "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
            "control_port=${controlSocket.localPort};timing_port=${timingSocket.localPort}"
        val setup = request("SETUP", url, mapOf("Transport" to transport))
        session = setup.headers["session"]?.substringBefore(';')?.trim()
        val params = setup.headers["transport"].orEmpty().split(';')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
            .toMap()
        serverPort = params["server_port"]?.toIntOrNull() ?: throw IOException("SETUP : pas de port audio")
        serverControlPort = params["control_port"]?.toIntOrNull() ?: 0
        log("AirPlay : ports audio $serverPort, contrôle $serverControlPort${if (encrypt) ", chiffré" else ""}")

        running = true
        sendSync(first = true)
        request("RECORD", url, mapOf("Range" to "npt=0-", "RTP-Info" to "seq=$seq;rtptime=$rtpTime"))
        request(
            "SET_PARAMETER", url, mapOf("Content-Type" to "text/parameters"),
            "volume: ${airplayVolume(volume)}\r\n",
        )
        thread(name = "raop-sync", isDaemon = true) {
            while (running) {
                try {
                    Thread.sleep(1000)
                    sendSync(first = false)
                } catch (_: InterruptedException) {
                    break
                } catch (e: IOException) {
                    if (running) log("AirPlay : synchronisation impossible (${e.message})")
                }
            }
        }
        log("AirPlay : lecture démarrée sur ${device.name}")
    }

    /** Règle le volume du récepteur, de 0 à 100 (bloquant : hors du thread principal). */
    fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100)
        if (!running) return
        request(
            "SET_PARAMETER", url, mapOf("Content-Type" to "text/parameters"),
            "volume: ${airplayVolume(volume)}\r\n",
        )
    }

    fun stop() {
        if (!running && rtsp == null) return
        running = false
        try {
            if (session != null) request("TEARDOWN", url)
        } catch (_: IOException) {
        }
        try {
            rtsp?.close()
        } catch (_: IOException) {
        }
        rtsp = null
        audioSocket.close()
        controlSocket.close()
        timingSocket.close()
    }

    // --- Audio ---------------------------------------------------------------------------------

    /** Reçoit le PCM capturé et l'envoie par paquets de 352 échantillons (fil de capture). */
    private fun feed(pcm: ByteArray, length: Int) {
        if (!running) return
        var offset = 0
        while (offset < length) {
            val count = minOf(length - offset, pending.size - pendingLength)
            System.arraycopy(pcm, offset, pending, pendingLength, count)
            pendingLength += count
            offset += count
            if (pendingLength == pending.size) {
                pendingLength = 0
                try {
                    sendAudioPacket()
                } catch (e: IOException) {
                    if (running) {
                        log("AirPlay : envoi impossible (${e.message}), arrêt")
                        running = false
                    }
                    return
                }
            }
        }
    }

    private fun sendAudioPacket() {
        val payload = encrypt(alacUncompressed(pending, FRAMES_PER_PACKET))
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = (if (firstPacket) 0xE0 else 0x60).toByte()
        putShort(packet, 2, seq)
        putInt(packet, 4, rtpTime)
        putInt(packet, 8, ssrc.toLong())
        System.arraycopy(payload, 0, packet, 12, payload.size)
        audioSocket.send(DatagramPacket(packet, packet.size, device.host, serverPort))
        history[seq % HISTORY_SIZE] = packet
        firstPacket = false
        seq = (seq + 1) and 0xFFFF
        rtpTime = (rtpTime + FRAMES_PER_PACKET) and 0xFFFFFFFFL
    }

    /** AES-128-CBC sur les blocs complets du paquet (le reste est laissé en clair), IV fixe. */
    private fun encrypt(data: ByteArray): ByteArray {
        val key = aesKey ?: return data
        val iv = aesIv ?: return data
        val blocks = data.size / 16 * 16
        if (blocks == 0) return data
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = aes.doFinal(data, 0, blocks)
        System.arraycopy(encrypted, 0, data, 0, blocks)
        return data
    }

    // --- Synchronisation (ports de contrôle et d'horloge) --------------------------------------

    /**
     * Paquet de synchronisation : « à l'instant T (NTP), le récepteur joue l'échantillon
     * rtpTime − latence ». Le son est donc joué [LATENCY_FRAMES] après sa capture.
     */
    private fun sendSync(first: Boolean) {
        if (serverControlPort == 0) return
        val now = rtpTime
        val packet = ByteArray(20)
        packet[0] = (if (first) 0x90 else 0x80).toByte()
        packet[1] = 0xD4.toByte()
        putShort(packet, 2, 7)
        putInt(packet, 4, (now - LATENCY_FRAMES) and 0xFFFFFFFFL)
        putNtp(packet, 8)
        putInt(packet, 16, now)
        controlSocket.send(DatagramPacket(packet, packet.size, device.host, serverControlPort))
    }

    private fun startUdpThreads() {
        // Horloge : le récepteur envoie des requêtes (0x52) auxquelles on répond avec l'heure NTP.
        thread(name = "raop-timing", isDaemon = true) {
            val buffer = ByteArray(128)
            timingSocket.soTimeout = 1000
            while (!timingSocket.isClosed) {
                try {
                    val request = DatagramPacket(buffer, buffer.size)
                    timingSocket.receive(request)
                    if (request.length < 32 || (buffer[1].toInt() and 0x7F) != 0x52) continue
                    val reply = ByteArray(32)
                    reply[0] = 0x80.toByte()
                    reply[1] = 0xD3.toByte()
                    putShort(reply, 2, 7)
                    System.arraycopy(buffer, 24, reply, 8, 8) // heure d'envoi du récepteur
                    putNtp(reply, 16)
                    putNtp(reply, 24)
                    timingSocket.send(DatagramPacket(reply, reply.size, request.socketAddress))
                } catch (_: SocketTimeoutException) {
                } catch (_: IOException) {
                    if (timingSocket.isClosed) break
                }
            }
        }
        // Contrôle : le récepteur peut redemander des paquets perdus (0x55).
        thread(name = "raop-control", isDaemon = true) {
            val buffer = ByteArray(128)
            controlSocket.soTimeout = 1000
            while (!controlSocket.isClosed) {
                try {
                    val request = DatagramPacket(buffer, buffer.size)
                    controlSocket.receive(request)
                    if (request.length < 8 || (buffer[1].toInt() and 0x7F) != 0x55) continue
                    val first = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
                    val count = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
                    for (i in 0 until minOf(count, HISTORY_SIZE)) {
                        val wanted = (first + i) and 0xFFFF
                        val packet = history[wanted % HISTORY_SIZE] ?: continue
                        val storedSeq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
                        if (storedSeq != wanted) continue
                        val resend = ByteArray(4 + packet.size)
                        resend[0] = 0x80.toByte()
                        resend[1] = 0xD6.toByte()
                        putShort(resend, 2, 1)
                        System.arraycopy(packet, 0, resend, 4, packet.size)
                        controlSocket.send(DatagramPacket(resend, resend.size, request.socketAddress))
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: IOException) {
                    if (controlSocket.isClosed) break
                }
            }
        }
    }

    // --- RTSP ----------------------------------------------------------------------------------

    private class Response(val code: Int, val message: String, val headers: Map<String, String>)

    @Synchronized
    private fun request(
        method: String,
        uri: String,
        extraHeaders: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Response {
        val socket = rtsp ?: throw IOException("RTSP fermé")
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
            .append("$method $uri RTSP/1.0\r\n")
            .append("CSeq: ${++cseq}\r\n")
            .append("User-Agent: iTunes/7.6.2 (Windows; N;)\r\n")
            .append("Client-Instance: $clientInstance\r\n")
            .append("DACP-ID: $clientInstance\r\n")
            .append("Active-Remote: 1986535575\r\n")
        session?.let { head.append("Session: $it\r\n") }
        authorization(method, uri)?.let { head.append("Authorization: $it\r\n") }
        extraHeaders.forEach { (name, value) -> head.append("$name: $value\r\n") }
        if (bodyBytes != null) head.append("Content-Length: ${bodyBytes.size}\r\n")
        head.append("\r\n")
        val out = socket.getOutputStream()
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        if (bodyBytes != null) out.write(bodyBytes)
        out.flush()

        val response = readResponse(socket.getInputStream())
        if (response.code == 401) {
            val challenge = response.headers["www-authenticate"].orEmpty()
            val alreadyTried = digest != null
            if (password.isNullOrEmpty()) throw AirPlayPasswordException(wrong = false)
            if (alreadyTried || !challenge.startsWith("Digest", ignoreCase = true)) {
                throw AirPlayPasswordException(wrong = true)
            }
            val realm = Regex("realm=\"([^\"]*)\"").find(challenge)?.groupValues?.get(1).orEmpty()
            val nonce = Regex("nonce=\"([^\"]*)\"").find(challenge)?.groupValues?.get(1).orEmpty()
            digest = realm to nonce
            return request(method, uri, extraHeaders, body) // même requête, authentifiée
        }
        if (response.code != 200) throw IOException("$method : ${response.code} ${response.message}")
        return response
    }

    /** En-tête « Authorization: Digest … » (utilisateur « iTunes », MD5) une fois le défi reçu. */
    private fun authorization(method: String, uri: String): String? {
        val (realm, nonce) = digest ?: return null
        val secret = password ?: return null
        val ha1 = md5("iTunes:$realm:$secret")
        val ha2 = md5("$method:$uri")
        val response = md5("$ha1:$nonce:$ha2")
        return "Digest username=\"iTunes\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun readResponse(input: InputStream): Response {
        val statusLine = readLine(input) ?: throw IOException("connexion fermée par le récepteur")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        var remaining = length
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong())
            if (skipped <= 0) {
                if (input.read() < 0) break
                remaining--
            } else {
                remaining -= skipped.toInt()
            }
        }
        val parts = statusLine.split(' ', limit = 3)
        return Response(parts.getOrNull(1)?.toIntOrNull() ?: 0, parts.getOrNull(2).orEmpty(), headers)
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (b == '\n'.code) return bytes.toString("UTF-8").trimEnd('\r')
            bytes.write(b)
        }
    }

    // --- Outils --------------------------------------------------------------------------------

    private fun rsaEncrypt(data: ByteArray): ByteArray {
        val modulus = BigInteger(1, Base64.decode(AIRPORT_MODULUS, Base64.DEFAULT))
        val exponent = BigInteger(1, Base64.decode("AQAB", Base64.DEFAULT))
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(data)
    }

    private fun base64(data: ByteArray) =
        Base64.encodeToString(data, Base64.NO_WRAP or Base64.NO_PADDING)

    private fun putShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value shr 8).toByte()
        buffer[offset + 1] = value.toByte()
    }

    private fun putInt(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = (value shr 24).toByte()
        buffer[offset + 1] = (value shr 16).toByte()
        buffer[offset + 2] = (value shr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }

    /** Heure actuelle au format NTP (secondes depuis 1900 + fraction sur 32 bits). */
    private fun putNtp(buffer: ByteArray, offset: Int) {
        val millis = System.currentTimeMillis()
        val seconds = millis / 1000 + NTP_EPOCH_OFFSET
        val fraction = (millis % 1000) * 0x100000000L / 1000
        putInt(buffer, offset, seconds and 0xFFFFFFFFL)
        putInt(buffer, offset + 4, fraction and 0xFFFFFFFFL)
    }

    companion object {
        const val FRAMES_PER_PACKET = 352
        private const val BYTES_PER_FRAME = 4 // 16 bits × 2 canaux
        private const val HISTORY_SIZE = 512
        private const val TIMEOUT_MS = 5000

        /** Retard de lecture demandé au récepteur : 2 s, comme Google Cast. */
        private const val LATENCY_FRAMES = 88_200L

        /** Volume de départ (0 à 100) : un niveau modéré. */
        const val DEFAULT_VOLUME = 50

        /** Volume AirPlay en dB : −30 (min) à 0 (max), −144 = muet. */
        fun airplayVolume(percent: Int): String {
            val db = if (percent <= 0) -144.0 else -30.0 + 30.0 * percent / 100.0
            return "%.6f".format(java.util.Locale.US, db)
        }

        private const val NTP_EPOCH_OFFSET = 2_208_988_800L

        /** Clé publique RSA des AirPort Express, utilisée par tous les récepteurs AirPlay 1. */
        private const val AIRPORT_MODULUS =
            "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDRKSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnhimNVvYFZeCXg/IdTQ+x4IRdiXNv5hEew=="

        /**
         * Trame ALAC « non compressée » : en-tête ALAC (paire de canaux, trame partielle, échappement),
         * puis les échantillons 16 bits big-endian bruts, puis la marque de fin. Tous les récepteurs
         * AirPlay savent la décoder et elle ne coûte rien à produire.
         */
        fun alacUncompressed(pcm: ByteArray, frames: Int): ByteArray {
            val bits = BitWriter(frames * BYTES_PER_FRAME + 16)
            bits.write(1, 3) // élément : paire de canaux (stéréo)
            bits.write(0, 4) // numéro d'élément
            bits.write(0, 12) // inutilisé
            bits.write(1, 1) // trame partielle : nombre d'échantillons indiqué
            bits.write(0, 2) // décalage
            bits.write(1, 1) // échappement : échantillons non compressés
            bits.write(frames.toLong(), 32)
            for (i in 0 until frames) {
                val base = i * BYTES_PER_FRAME
                val left = ((pcm[base + 1].toInt() and 0xFF) shl 8) or (pcm[base].toInt() and 0xFF)
                val right = ((pcm[base + 3].toInt() and 0xFF) shl 8) or (pcm[base + 2].toInt() and 0xFF)
                bits.write(left.toLong(), 16)
                bits.write(right.toLong(), 16)
            }
            bits.write(7, 3) // fin de trame
            return bits.toByteArray()
        }
    }

    /** Écriture de bits, poids fort en premier. */
    private class BitWriter(capacity: Int) {
        private val out = ByteArray(capacity)
        private var bytePos = 0
        private var bitPos = 0

        fun write(value: Long, count: Int) {
            for (i in count - 1 downTo 0) {
                val bit = ((value shr i) and 1L).toInt()
                if (bit == 1) out[bytePos] = (out[bytePos].toInt() or (0x80 ushr bitPos)).toByte()
                bitPos++
                if (bitPos == 8) {
                    bitPos = 0
                    bytePos++
                }
            }
        }

        fun toByteArray(): ByteArray = out.copyOf(if (bitPos == 0) bytePos else bytePos + 1)
    }
}

/** Le récepteur exige un mot de passe (AirMedia protégé) : [wrong] si celui fourni est refusé. */
class AirPlayPasswordException(val wrong: Boolean) : IOException(
    if (wrong) "mot de passe AirPlay refusé" else "mot de passe AirPlay requis"
)
