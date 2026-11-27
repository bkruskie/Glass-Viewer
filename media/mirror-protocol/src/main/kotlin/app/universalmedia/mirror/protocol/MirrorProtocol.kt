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

    /** Reads the next packet, or null at a clean end of stream. */
    fun read(input: DataInputStream): Packet? {
        val code = input.read()
        if (code < 0) return null
        val type = PacketType.from(code) ?: throw IOException("Unknown packet type $code")
        val time = input.readLong()
        val length = input.readInt()
        if (length < 0 || length > MAX_PAYLOAD) throw IOException("Bad packet length $length")
        val payload = ByteArray(length)
        try {
            input.readFully(payload)
        } catch (e: EOFException) {
            return null
        }
        return Packet(type, time, payload)
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
    /** The sender is stopping. */
    BYE(9);

    companion object {
        fun from(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

class Packet(val type: PacketType, val timeUs: Long, val payload: ByteArray)
