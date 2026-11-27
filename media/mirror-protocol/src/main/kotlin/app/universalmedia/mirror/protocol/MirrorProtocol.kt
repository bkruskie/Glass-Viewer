package app.universalmedia.mirror.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * Screen-mirroring wire format between the phone and the TV receiver app.
 *
 * The phone connects to the receiver over TCP and sends the 5-byte greeting
 * "UMIR" + version, then a stream of packets:
 *   type (1 byte) · presentation time µs (8) · payload length (4) · payload
 * H.264 is sent in Annex-B form exactly as Android's encoder produces it.
 */
object MirrorProtocol {
    /** DNS-SD service type the TV receiver advertises on the local network. */
    const val SERVICE_TYPE = "_umreceiver._tcp."

    /** DNS-SD TXT key: "1" when the receiver plays mirrored sound (receiver v2+). */
    const val TXT_AUDIO = "audio"
    const val VERSION: Byte = 1
    private val MAGIC = byteArrayOf('U'.code.toByte(), 'M'.code.toByte(), 'I'.code.toByte(), 'R'.code.toByte())

    /** Max payload accepted, to protect the receiver from garbage. */
    const val MAX_PAYLOAD = 8 * 1024 * 1024

    fun writeGreeting(out: DataOutputStream) {
        out.write(MAGIC)
        out.writeByte(VERSION.toInt())
        out.flush()
    }

    /** Returns the sender's protocol version, or throws if this isn't a mirroring stream. */
    fun readGreeting(input: DataInputStream): Int {
        val magic = ByteArray(4)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a Universal Media mirroring stream")
        return input.readUnsignedByte()
    }

    fun write(out: DataOutputStream, packet: Packet) {
        out.writeByte(packet.type.code)
        out.writeLong(packet.timeUs)
        out.writeInt(packet.payload.size)
        out.write(packet.payload)
    }

    /** Reads the next packet, or null at a clean end of stream. Unknown packet types are skipped. */
    fun read(input: DataInputStream): Packet? {
        while (true) {
            val code = input.read()
            if (code < 0) return null
            val time = input.readLong()
            val length = input.readInt()
            if (length < 0 || length > MAX_PAYLOAD) throw IOException("Bad packet length $length")
            val payload = ByteArray(length)
            try {
                input.readFully(payload)
            } catch (e: EOFException) {
                return null
            }
            val type = PacketType.from(code) ?: continue // newer sender: ignore what we don't understand
            return Packet(type, time, payload)
        }
    }

    /** Audio config payload: sample rate, channel count, then the AAC AudioSpecificConfig. */
    fun audioConfigPayload(sampleRate: Int, channels: Int, asc: ByteArray): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        DataOutputStream(buffer).use {
            it.writeInt(sampleRate)
            it.writeInt(channels)
            it.write(asc)
        }
        return buffer.toByteArray()
    }

    data class AudioConfig(val sampleRate: Int, val channels: Int, val asc: ByteArray)

    fun parseAudioConfig(payload: ByteArray): AudioConfig {
        if (payload.size < 8) throw IOException("Short audio config packet")
        val input = DataInputStream(payload.inputStream())
        val rate = input.readInt()
        val channels = input.readInt()
        if (rate !in 8_000..96_000 || channels !in 1..8) throw IOException("Bad audio config")
        return AudioConfig(rate, channels, payload.copyOfRange(8, payload.size))
    }

    /** Config packet payload: width, height, then the codec config (SPS/PPS). */
    fun configPayload(width: Int, height: Int, codecConfig: ByteArray): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        DataOutputStream(buffer).use {
            it.writeInt(width)
            it.writeInt(height)
            it.write(codecConfig)
        }
        return buffer.toByteArray()
    }

    data class VideoConfig(val width: Int, val height: Int, val codecConfig: ByteArray)

    fun parseConfig(payload: ByteArray): VideoConfig {
        if (payload.size < 8) throw IOException("Short config packet")
        val input = DataInputStream(payload.inputStream())
        val width = input.readInt()
        val height = input.readInt()
        return VideoConfig(width, height, payload.copyOfRange(8, payload.size))
    }
}

enum class PacketType(val code: Int) {
    /** Video size and H.264 SPS/PPS; sent first and whenever the encoder restarts. */
    VIDEO_CONFIG(1),
    VIDEO_FRAME(2),
    VIDEO_KEY_FRAME(3),
    /** AAC sample rate, channels and AudioSpecificConfig (receiver v2+). */
    AUDIO_CONFIG(4),
    /** One raw AAC frame. */
    AUDIO_FRAME(5),
    /** The sender is stopping. */
    BYE(9);

    companion object {
        fun from(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

class Packet(val type: PacketType, val timeUs: Long, val payload: ByteArray)
