package io.ucc.core.platform

/** SHA-256 digest of [bytes]. 32 bytes. */
public expect fun sha256(bytes: ByteArray): ByteArray

private const val HEX: String = "0123456789abcdef"

/** Lower-case hex, two characters per byte (the `%02x` format the JVM code used). */
public fun ByteArray.toHexLower(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return sb.toString()
}
