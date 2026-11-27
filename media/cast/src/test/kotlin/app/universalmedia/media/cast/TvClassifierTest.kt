package app.universalmedia.media.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class TvClassifierTest {
    private fun answer(location: String, st: String, server: String = "") =
        DlnaProtocol.SsdpAnswer(location, location, st, server)

    @Test
    fun recognisesRokuRendererOtherTvAndSkipsRouters() {
        val answers = listOf(
            answer("http://192.168.1.30:8060/", "roku:ecp", "Roku/9.0 UPnP/1.0 Roku/9.0"),
            answer("http://192.168.1.40:9197/dmr", "urn:schemas-upnp-org:device:MediaRenderer:1"),
            answer("http://192.168.1.50:1990/desc.xml", "urn:dial-multiscreen-org:service:dial:1", "WebOS/4.0 UPnP/1.0"),
            answer("http://192.168.1.1:5000/igd.xml", "urn:schemas-upnp-org:device:InternetGatewayDevice:1", "Linux UPnP/1.0 MiniUPnPd"),
        )
        val identities = mapOf(
            "http://192.168.1.40:9197/dmr" to DlnaProtocol.DeviceIdentity(
                "[TV] Living Room", "Samsung Electronics", "QN65Q80", listOf("urn:schemas-upnp-org:device:MediaRenderer:1"), true,
            ),
            "http://192.168.1.50:1990/desc.xml" to DlnaProtocol.DeviceIdentity(
                "Bedroom TV", "LG Electronics", "OLED55C1", listOf("urn:dial-multiscreen-org:device:dial:1"), false,
            ),
            "http://192.168.1.1:5000/igd.xml" to DlnaProtocol.DeviceIdentity(
                "Router", "Netgear", "R7000", listOf("urn:schemas-upnp-org:device:InternetGatewayDevice:1"), false,
            ),
        )
        val tvs = TvClassifier.classify(answers, identities)
        assertEquals(listOf("192.168.1.40", "192.168.1.30", "192.168.1.50"), tvs.map { it.host })
        assertEquals(NetworkTv.Kind.DLNA_READY, tvs[0].kind)
        assertEquals("Samsung", tvs[0].brand)
        assertEquals(NetworkTv.Kind.ROKU, tvs[1].kind)
        assertEquals(NetworkTv.Kind.TV_OTHER, tvs[2].kind)
        assertEquals("LG", tvs[2].brand)
    }

    @Test
    fun parsesSearchAllAnswerAndIdentity() {
        val a = DlnaProtocol.parseAnswer("HTTP/1.1 200 OK\r\nST: roku:ecp\r\nLOCATION: http://10.0.0.5:8060/\r\nUSN: uuid:roku:ecp:X123\r\nSERVER: Roku/12\r\n\r\n")!!
        assertEquals("roku:ecp", a.st)
        val id = DlnaProtocol.parseIdentity(
            "<root><device><deviceType>urn:roku-com:device:player:1-0</deviceType><friendlyName>Den Roku</friendlyName>" +
                "<manufacturer>Roku</manufacturer><modelName>Roku Ultra</modelName></device></root>",
        )!!
        assertEquals("Den Roku", id.friendlyName)
        assertEquals(false, id.hasAvTransport)
    }

    @Test
    fun parsesRokuPlayer() {
        val s = RokuProtocol.parsePlayer(
            "<?xml version=\"1.0\"?><player error=\"false\" state=\"play\"><position>12345 ms</position><duration>600000 ms</duration></player>",
        )!!
        assertEquals(12345L, s.positionMs)
        assertEquals(600000L, s.durationMs)
        assertEquals(true, s.playing)
    }
}
