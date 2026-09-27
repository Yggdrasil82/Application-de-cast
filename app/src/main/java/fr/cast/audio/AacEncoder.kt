package fr.cast.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * Encode du PCM 16 bits en AAC-LC et produit des trames ADTS autonomes,
 * lisibles en flux continu par les enceintes Google Cast et la plupart des lecteurs.
 */
class AacEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    bitRate: Int,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private val bytesPerSecond = sampleRate * channels * 2
    private var totalBytes = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Envoie [length] octets de PCM à l'encodeur puis récupère les trames prêtes. */
    fun encode(pcm: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) {
                drain()
                continue
            }
            val buffer = codec.getInputBuffer(index) ?: continue
            buffer.clear()
            val chunk = minOf(buffer.remaining(), length - offset)
            buffer.put(pcm, offset, chunk)
            val ptsUs = totalBytes * 1_000_000L / bytesPerSecond
            codec.queueInputBuffer(index, 0, chunk, ptsUs, 0)
            totalBytes += chunk
            offset += chunk
            drain()
        }
    }

    private fun drain() {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return // INFO_TRY_AGAIN_LATER ou INFO_OUTPUT_FORMAT_CHANGED
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!isConfig && info.size > 0) {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null) {
                    val frame = ByteArray(ADTS_HEADER_SIZE + info.size)
                    writeAdtsHeader(frame, frame.size)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.get(frame, ADTS_HEADER_SIZE, info.size)
                    onFrame(frame)
                }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    fun release() {
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
        }
        codec.release()
    }

    private fun writeAdtsHeader(packet: ByteArray, packetLength: Int) {
        val profile = 2 // AAC LC
        val freqIndex = SAMPLE_RATE_INDEX[sampleRate] ?: 3
        val channelConfig = channels
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF1.toByte() // MPEG-4, pas de CRC
        packet[2] = (((profile - 1) shl 6) or (freqIndex shl 2) or (channelConfig shr 2)).toByte()
        packet[3] = (((channelConfig and 3) shl 6) or (packetLength shr 11)).toByte()
        packet[4] = ((packetLength and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLength and 7) shl 5) or 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }

    companion object {
        private const val ADTS_HEADER_SIZE = 7
        private val SAMPLE_RATE_INDEX = mapOf(
            96000 to 0, 88200 to 1, 64000 to 2, 48000 to 3, 44100 to 4, 32000 to 5,
            24000 to 6, 22050 to 7, 16000 to 8, 12000 to 9, 11025 to 10, 8000 to 11,
        )
    }
}
