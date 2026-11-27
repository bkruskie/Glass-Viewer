package app.universalmedia.media.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProtocolTest {
    @Test
    fun parsesUploadRequest() {
        val r = TransferProtocol.parseRequestLine("PUT /abc123/up?name=My%20Clip%20%C3%A9.mp4&size=10 HTTP/1.1")!!
        assertEquals("PUT", r.method)
        assertEquals("/abc123/up", r.path)
        assertEquals("My Clip é.mp4", r.query["name"])
        assertEquals("10", r.query["size"])
    }

    @Test
    fun decodeToleratesBadEscapes() {
        assertEquals("100% a b", TransferProtocol.decode("100%25 a+b"))
        assertEquals("50%", TransferProtocol.decode("50%"))
        assertEquals("%zz", TransferProtocol.decode("%zz"))
    }

    @Test
    fun safeFileNameStripsFoldersAndReservedCharacters() {
        assertEquals("passwd", TransferProtocol.safeFileName("../../etc/passwd"))
        assertEquals("evil.mp4", TransferProtocol.safeFileName("C:\\Users\\x\\evil.mp4"))
        assertEquals("ab.mp4", TransferProtocol.safeFileName("a<b>.mp4"))
        assertEquals("file", TransferProtocol.safeFileName(".."))
    }

    @Test
    fun listJsonEscapes() {
        val json = TransferProtocol.listJson(listOf(SharedFile("1", "a \"q\".mp4", "content://x", "video/mp4", 5)), "Pixel")
        assertEquals("{\"device\":\"Pixel\",\"files\":[{\"id\":\"1\",\"name\":\"a \\\"q\\\".mp4\",\"type\":\"video/mp4\",\"size\":5}]}", json)
        assertTrue(TransferProtocol.PAGE.contains("base+'up?name='"))
    }
}
