package io.ucc.core.platform

/**
 * Percent encoding/decoding with byte-for-byte the behaviour the JVM
 * implementation had, so share links keep round-tripping identically:
 *
 * - [encode] == `URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~")`
 *   (unreserved: `A-Z a-z 0-9 . - * _ ~`; everything else is upper-case `%XX` of its UTF-8 bytes)
 * - [decode] == `URLDecoder.decode(s, UTF_8)` for input that contains no `+`
 *   (`+` is kept literally; callers that wanted form semantics never existed here).
 *   Consecutive `%XX` groups are decoded together, malformed UTF-8 becomes U+FFFD,
 *   and an incomplete or non-hex escape throws [IllegalArgumentException].
 */
public object UrlCodec {
    private const val UPPER_HEX = "0123456789ABCDEF"

    public fun encode(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '*' || ch == '_' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append(UPPER_HEX[c ushr 4]).append(UPPER_HEX[c and 0x0f])
            }
        }
        return sb.toString()
    }

    public fun decode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val sb = StringBuilder(s.length)
        val n = s.length
        var i = 0
        val run = ByteArray(n / 3 + 1)
        while (i < n) {
            val c = s[i]
            if (c != '%') { sb.append(c); i++; continue }
            var pos = 0
            while (i < n && s[i] == '%') {
                if (i + 2 >= n) throw IllegalArgumentException("URLDecoder: Incomplete trailing escape (%) pattern")
                val hi = hexVal(s[i + 1]); val lo = hexVal(s[i + 2])
                if (hi < 0 || lo < 0) throw IllegalArgumentException("URLDecoder: Illegal hex characters in escape (%) pattern")
                run[pos++] = ((hi shl 4) or lo).toByte()
                i += 3
            }
            sb.append(run.decodeToString(0, pos))
        }
        return sb.toString()
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
