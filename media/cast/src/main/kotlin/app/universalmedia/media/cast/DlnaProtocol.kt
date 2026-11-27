package app.universalmedia.media.cast

import app.universalmedia.media.detection.MiniXml
import app.universalmedia.media.detection.UrlUtils
import app.universalmedia.media.detection.XmlElement

/** A UPnP/DLNA media renderer (smart TV, speaker, console) found on the network. */
data class DlnaRenderer(
    val usn: String,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val avTransportUrl: String,
    val renderingControlUrl: String?,
)

/** Pure protocol helpers (no I/O) for SSDP discovery and UPnP AV control. */
object DlnaProtocol {
    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    val searchRequest: String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $RENDERER\r\n" +
            "USER-AGENT: Android UPnP/1.0 UniversalMedia/1.0\r\n\r\n"

    /** Asks every UPnP device on the network to answer (TVs, Rokus, speakers, routers…). */
    val searchAllRequest: String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: ssdp:all\r\n" +
            "USER-AGENT: Android UPnP/1.0 UniversalMedia/1.0\r\n\r\n"

    /** Raw SSDP answer: where its description is, its id, what it says it is, and its server string. */
    data class SsdpAnswer(val location: String, val usn: String, val st: String, val server: String)

    fun parseAnswer(text: String): SsdpAnswer? {
        val lines = text.split("\r\n", "\n")
        if (lines.firstOrNull()?.startsWith("HTTP/1.1 200", ignoreCase = true) != true) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim()
        }.toMap()
        val location = headers["LOCATION"]?.takeIf { UrlUtils.isHttp(it) } ?: return null
        return SsdpAnswer(
            location = location,
            usn = headers["USN"]?.substringBefore("::")?.ifBlank { null } ?: location,
            st = headers["ST"].orEmpty(),
            server = headers["SERVER"].orEmpty(),
        )
    }

    /** Basic identity from a UPnP device description. */
    data class DeviceIdentity(
        val friendlyName: String?,
        val manufacturer: String?,
        val modelName: String?,
        val deviceTypes: List<String>,
        val hasAvTransport: Boolean,
    )

    fun parseIdentity(xml: String): DeviceIdentity? {
        val root = MiniXml.parse(xml) ?: return null
        val device = root.child("device") ?: return null
        fun devices(d: XmlElement): List<XmlElement> = listOf(d) + d.child("deviceList")?.all("device").orEmpty().flatMap { devices(it) }
        val all = devices(device)
        return DeviceIdentity(
            friendlyName = device.child("friendlyName")?.text?.ifBlank { null },
            manufacturer = device.child("manufacturer")?.text?.ifBlank { null },
            modelName = device.child("modelName")?.text?.ifBlank { null },
            deviceTypes = all.mapNotNull { it.child("deviceType")?.text },
            hasAvTransport = allServices(device).any {
                it.child("serviceType")?.text?.startsWith("urn:schemas-upnp-org:service:AVTransport") == true
            },
        )
    }

    /** Parses an SSDP response; returns (LOCATION, USN) or null. */
    fun parseSearchResponse(text: String): Pair<String, String>? {
        val lines = text.split("\r\n", "\n")
        if (lines.firstOrNull()?.startsWith("HTTP/1.1 200", ignoreCase = true) != true) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim()
        }.toMap()
        val location = headers["LOCATION"]?.takeIf { UrlUtils.isHttp(it) } ?: return null
        val usn = headers["USN"]?.substringBefore("::")?.ifBlank { null } ?: location
        return location to usn
    }

    /** Reads a device description; null if the device can't play media (no AVTransport). */
    fun parseDescription(xml: String, location: String, usn: String): DlnaRenderer? {
        val root = MiniXml.parse(xml) ?: return null
        val base = root.child("URLBase")?.text?.takeIf { it.isNotBlank() } ?: location
        val device = root.child("device") ?: return null
        val services = allServices(device)
        val av = services.firstOrNull { it.child("serviceType")?.text?.startsWith("urn:schemas-upnp-org:service:AVTransport") == true }
            ?: return null
        val rc = services.firstOrNull { it.child("serviceType")?.text?.startsWith("urn:schemas-upnp-org:service:RenderingControl") == true }
        val avUrl = av.child("controlURL")?.text?.let { UrlUtils.resolve(base, it) } ?: return null
        return DlnaRenderer(
            usn = usn,
            friendlyName = device.child("friendlyName")?.text?.ifBlank { null } ?: "TV",
            manufacturer = device.child("manufacturer")?.text,
            modelName = device.child("modelName")?.text,
            avTransportUrl = avUrl,
            renderingControlUrl = rc?.child("controlURL")?.text?.let { UrlUtils.resolve(base, it) },
        )
    }

    private fun allServices(device: XmlElement): List<XmlElement> =
        device.child("serviceList")?.all("service").orEmpty() +
            device.child("deviceList")?.all("device").orEmpty().flatMap { allServices(it) }

    /** SOAP request body for [action] on [serviceType]. */
    fun soapEnvelope(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
        for ((name, value) in args) append("<").append(name).append(">").append(escape(value)).append("</").append(name).append(">")
        append("</u:").append(action).append("></s:Body></s:Envelope>")
    }

    fun soapActionHeader(serviceType: String, action: String) = "\"$serviceType#$action\""

    /** DIDL-Lite metadata; many TVs (notably Samsung and LG) refuse to play without it. */
    fun didl(title: String, url: String, mimeType: String): String {
        val upnpClass = when {
            mimeType.startsWith("audio") -> "object.item.audioItem.musicTrack"
            mimeType.startsWith("image") -> "object.item.imageItem"
            else -> "object.item.videoItem"
        }
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${escape(title)}</dc:title>" +
            "<upnp:class>$upnpClass</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mimeType:$DLNA_FEATURES\">${escape(url)}</res></item></DIDL-Lite>"
    }

    /** DLNA.ORG flags: byte seek supported, streaming transfer, DLNA 1.5. */
    const val DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    /** "1:02:03" for UPnP Seek / position values. */
    fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank() || value.startsWith("NOT_IMPLEMENTED")) return null
        val parts = value.substringBefore('.').split(':').map { it.toLongOrNull() ?: return null }
        if (parts.size != 3) return null
        return ((parts[0] * 3600) + (parts[1] * 60) + parts[2]) * 1000
    }

    /** Value of an element in a SOAP response body (namespace prefixes ignored). */
    fun responseValue(xml: String, name: String): String? {
        fun find(e: XmlElement): XmlElement? = if (e.name == name) e else e.children.firstNotNullOfOrNull { find(it) }
        return MiniXml.parse(xml)?.let(::find)?.text
    }

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
