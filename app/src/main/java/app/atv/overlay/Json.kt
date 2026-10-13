package app.atv.overlay

class HttpException(val code: Int, message: String) : Exception(message)

object Json {

    fun esc(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }

    fun str(s: String?): String = if (s == null) "null" else "\"" + esc(s) + "\""

    fun error(msg: String): String = "{\"error\":\"" + esc(msg) + "\"}"

    fun arr(items: List<String>): String = items.joinToString(",", "[", "]")

    class Obj {
        private val parts = mutableListOf<String>()

        fun put(k: String, v: String?): Obj = apply { parts += "\"$k\":${str(v)}" }
        fun put(k: String, v: Number?): Obj = apply { parts += "\"$k\":${v?.toString() ?: "null"}" }
        fun put(k: String, v: Boolean): Obj = apply { parts += "\"$k\":$v" }
        fun putRaw(k: String, v: String): Obj = apply { parts += "\"$k\":$v" }

        override fun toString(): String = parts.joinToString(",", "{", "}")
    }

    fun parseFlat(s: String): Map<String, String> {
        val fail: (String) -> Nothing = { m -> throw HttpException(400, "invalid JSON body: $m") }
        var i = 0
        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
        fun string(): String {
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("truncated escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) fail("truncated \\u escape")
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("bad \\u escape"))
                                i += 4
                            }
                            else -> fail("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return fail("unterminated string")
        }
        fun scalar(): String? {
            ws()
            if (i >= s.length) fail("unexpected end of body")
            return when (s[i]) {
                '"' -> string()
                '{', '[' -> fail("nested objects/arrays are not supported")
                else -> {
                    val start = i
                    while (i < s.length && !",}".contains(s[i]) && !s[i].isWhitespace()) i++
                    when (val raw = s.substring(start, i)) {
                        "null" -> null
                        "true", "false" -> raw
                        else -> if (raw.matches(Regex("""-?\d+(\.\d+)?([eE][-+]?\d+)?"""))) raw
                        else fail("unexpected value '$raw'")
                    }
                }
            }
        }
        ws()
        if (i >= s.length || s[i] != '{') fail("expected a JSON object")
        i++
        val out = LinkedHashMap<String, String>()
        ws()
        if (i < s.length && s[i] == '}') return out
        while (true) {
            ws()
            if (i >= s.length || s[i] != '"') fail("expected a \"key\"")
            val key = string()
            ws()
            if (i >= s.length || s[i] != ':') fail("expected ':' after \"$key\"")
            i++
            scalar()?.let { out[key] = it }
            ws()
            if (i >= s.length) fail("expected ',' or '}'")
            when (s[i++]) {
                ',' -> {}
                '}' -> {
                    ws()
                    if (i != s.length) fail("trailing characters after object")
                    return out
                }
                else -> fail("expected ',' or '}'")
            }
        }
    }
}
