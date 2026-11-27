package app.universalmedia.media.detection

/** Minimal XML element tree; enough for DASH manifests. Namespaces prefixes are dropped. */
class XmlElement(val name: String, val attributes: Map<String, String>) {
    val children = mutableListOf<XmlElement>()
    var text: String = ""
        internal set

    fun child(name: String): XmlElement? = children.firstOrNull { it.name == name }
    fun all(name: String): List<XmlElement> = children.filter { it.name == name }
    operator fun get(attribute: String): String? = attributes[attribute]
}

object MiniXml {
    private val attr = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")

    /** Parses [xml] into a tree and returns the root element, or null if malformed. */
    fun parse(xml: String): XmlElement? {
        val stack = ArrayDeque<XmlElement>()
        var root: XmlElement? = null
        var i = 0
        val text = StringBuilder()
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            if (lt > i) text.append(xml, i, lt)
            when {
                xml.startsWith("<!--", lt) -> { i = xml.indexOf("-->", lt).let { if (it < 0) xml.length else it + 3 }; continue }
                xml.startsWith("<![CDATA[", lt) -> {
                    val end = xml.indexOf("]]>", lt).let { if (it < 0) xml.length else it }
                    text.append(xml, lt + 9, end)
                    i = end + 3
                    continue
                }
                xml.startsWith("<?", lt) || xml.startsWith("<!", lt) -> {
                    i = xml.indexOf('>', lt).let { if (it < 0) xml.length else it + 1 }
                    continue
                }
            }
            val gt = findTagEnd(xml, lt)
            if (gt < 0) return null
            val raw = xml.substring(lt + 1, gt)
            if (raw.startsWith("/")) {
                val closed = stack.removeLastOrNull() ?: return null
                closed.text = decode(text.toString().trim())
                text.clear()
            } else {
                val selfClosing = raw.endsWith("/")
                val body = if (selfClosing) raw.dropLast(1) else raw
                val tag = body.takeWhile { !it.isWhitespace() }
                val attrs = attr.findAll(body.substring(tag.length)).associate { m ->
                    m.groupValues[1].substringAfter(':') to decode(m.groups[3]?.value ?: m.groups[4]?.value ?: "")
                }
                val element = XmlElement(tag.substringAfter(':'), attrs)
                stack.lastOrNull()?.children?.add(element) ?: run { if (root == null) root = element }
                if (!selfClosing) stack.addLast(element)
                text.clear()
            }
            i = gt + 1
        }
        return root
    }

    private fun findTagEnd(xml: String, from: Int): Int {
        var quote: Char? = null
        for (j in from + 1 until xml.length) {
            val c = xml[j]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return j
            }
        }
        return -1
    }

    private fun decode(s: String): String =
        if ('&' !in s) s else s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
}
