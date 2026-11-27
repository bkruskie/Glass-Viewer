package app.universalmedia.media.detection

/**
 * Minimal JSON reader for site-adapter API responses. Produces
 * Map<String, Any?>, List<Any?>, String, Double, Boolean or null.
 * Kept in-house so the engine has no dependencies and stays multiplatform-ready.
 */
internal object MiniJson {

    class ParseException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val value = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw ParseException("Trailing characters at ${p.pos}")
        return value
    }

    fun parseOrNull(text: String): Any? = try {
        parse(text)
    } catch (e: ParseException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    } catch (e: NumberFormatException) {
        null
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWs()
            if (atEnd()) throw ParseException("Unexpected end")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw ParseException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("Expected $word at $pos")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (s[pos] != '"') throw ParseException("Expected key at $pos")
                val key = readString()
                skipWs()
                if (s[pos] != ':') throw ParseException("Expected ':' at $pos")
                pos++
                out[key] = readValue()
                skipWs()
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw ParseException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (s[pos] == ']') { pos++; return out }
            while (true) {
                out += readValue()
                skipWs()
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw ParseException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw ParseException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return s.substring(start, pos).toDouble()
        }
    }
}

/** Typed accessors used by site adapters. */
@Suppress("UNCHECKED_CAST")
internal object MiniJsonAccess {
    fun parse(text: String): Any? = MiniJson.parseOrNull(text)

    fun obj(target: Any?, key: String): Map<String, Any?>? = (target as? Map<*, *>)?.get(key) as? Map<String, Any?>

    fun arr(target: Any?, key: String): List<Any?> = ((target as? Map<*, *>)?.get(key) as? List<Any?>).orEmpty()

    fun str(target: Any?, key: String): String? = when (val v = (target as? Map<*, *>)?.get(key)) {
        is String -> v
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        is Boolean -> v.toString()
        is List<*> -> v.firstOrNull() as? String // some APIs return "x" or ["x", ...]
        else -> null
    }

    fun num(target: Any?, key: String): Double? = when (val v = (target as? Map<*, *>)?.get(key)) {
        is Double -> v
        is String -> v.toDoubleOrNull()
        else -> null
    }
}
