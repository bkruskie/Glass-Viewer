package app.universalmedia.media.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaProtocolTest {

    @Test
    fun parsesSsdpResponse() {
        val response = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.50:9197/dmr\r\n" +
            "USN: uuid:1234-abcd::urn:schemas-upnp-org:device:MediaRenderer:1\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals("http://192.168.1.50:9197/dmr" to "uuid:1234-abcd", DlnaProtocol.parseSearchResponse(response))
        assertNull(DlnaProtocol.parseSearchResponse("NOTIFY * HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun parsesDescriptionWithEmbeddedDevices() {
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>[TV] Living Room &amp; Den</friendlyName>
                <manufacturer>Samsung Electronics</manufacturer>
                <modelName>QN65</modelName>
                <serviceList>
                  <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/upnp/control/RenderingControl1</controlURL></service>
                </serviceList>
                <deviceList><device><serviceList>
                  <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>upnp/control/AVTransport1</controlURL></service>
                </serviceList></device></deviceList>
              </device>
            </root>
        """.trimIndent()
        val r = DlnaProtocol.parseDescription(xml, "http://192.168.1.50:9197/dmr", "uuid:1")!!
        assertEquals("[TV] Living Room & Den", r.friendlyName)
        assertEquals("http://192.168.1.50:9197/upnp/control/AVTransport1", r.avTransportUrl)
        assertEquals("http://192.168.1.50:9197/upnp/control/RenderingControl1", r.renderingControlUrl)
        assertNull(DlnaProtocol.parseDescription("<root><device><friendlyName>NAS</friendlyName></device></root>", "http://x/", "u"))
    }

    @Test
    fun buildsSoapWithEscapedDidl() {
        val didl = DlnaProtocol.didl("Tom & Jerry", "http://192.168.1.2:8080/m/a/b.mp4?x=1&y=2", "video/mp4")
        val body = DlnaProtocol.soapEnvelope(
            DlnaProtocol.AV_TRANSPORT, "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to "http://h/x.mp4", "CurrentURIMetaData" to didl),
        )
        assertTrue(body.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(body.contains("&lt;dc:title&gt;Tom &amp;amp; Jerry&lt;/dc:title&gt;"))
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", DlnaProtocol.soapActionHeader(DlnaProtocol.AV_TRANSPORT, "Play"))
    }

    @Test
    fun timesAndResponses() {
        assertEquals("1:02:03", DlnaProtocol.formatTime(3_723_400))
        assertEquals(3_723_000L, DlnaProtocol.parseTime("01:02:03.500"))
        assertNull(DlnaProtocol.parseTime("NOT_IMPLEMENTED"))
        val resp = "<s:Envelope><s:Body><u:GetPositionInfoResponse><Track>1</Track><RelTime>0:00:42</RelTime>" +
            "<TrackDuration>0:10:00</TrackDuration></u:GetPositionInfoResponse></s:Body></s:Envelope>"
        assertEquals("0:00:42", DlnaProtocol.responseValue(resp, "RelTime"))
    }
}
