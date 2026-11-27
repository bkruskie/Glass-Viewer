package app.universalmedia.mirror.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorProtocolTest {

    @Test
    fun roundTripsGreetingConfigAndFrames() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            MirrorProtocol.writeGreeting(out)
            MirrorProtocol.write(out, Packet(PacketType.VIDEO_CONFIG, 0, MirrorProtocol.configPayload(1280, 720, byteArrayOf(0, 0, 0, 1, 0x67))))
            MirrorProtocol.write(out, Packet(PacketType.VIDEO_KEY_FRAME, 33_000, byteArrayOf(9, 8, 7)))
        }
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(1, MirrorProtocol.readGreeting(input))
        val config = MirrorProtocol.read(input)!!
        assertEquals(PacketType.VIDEO_CONFIG, config.type)
        val parsed = MirrorProtocol.parseConfig(config.payload)
        assertEquals(1280, parsed.width)
        assertEquals(720, parsed.height)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67), parsed.codecConfig)
        val frame = MirrorProtocol.read(input)!!
        assertEquals(PacketType.VIDEO_KEY_FRAME, frame.type)
        assertEquals(33_000L, frame.timeUs)
        assertArrayEquals(byteArrayOf(9, 8, 7), frame.payload)
        assertNull(MirrorProtocol.read(input))
    }

    @Test(expected = IOException::class)
    fun rejectsForeignStreams() {
        MirrorProtocol.readGreeting(DataInputStream(ByteArrayInputStream("GET / HTTP/1.1".toByteArray())))
    }

    @Test
    fun skipsUnknownPacketTypesAndRoundTripsAudioConfig() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            // A packet type from a future sender.
            out.writeByte(77); out.writeLong(1); out.writeInt(3); out.write(byteArrayOf(1, 2, 3))
            MirrorProtocol.write(out, Packet(PacketType.AUDIO_CONFIG, 0, MirrorProtocol.audioConfigPayload(48_000, 2, byteArrayOf(0x11, 0x90.toByte()))))
        }
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        val packet = MirrorProtocol.read(input)!!
        assertEquals(PacketType.AUDIO_CONFIG, packet.type)
        val config = MirrorProtocol.parseAudioConfig(packet.payload)
        assertEquals(48_000, config.sampleRate)
        assertEquals(2, config.channels)
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), config.asc)
        assertNull(MirrorProtocol.read(input))
    }
}
