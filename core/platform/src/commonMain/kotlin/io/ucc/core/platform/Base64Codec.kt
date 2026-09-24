package io.ucc.core.platform

import kotlin.io.encoding.Base64

/**
 * Base64 with the exact semantics the JVM code relied on
 * (`java.util.Base64` basic / URL-safe encoders, strict basic decoder).
 */
public object Base64Codec {
    /** Standard alphabet, padded — `Base64.getEncoder().encodeToString`. */
    public fun encode(bytes: ByteArray): String = Base64.Default.encode(bytes)

    /** URL-safe alphabet, no padding — `Base64.getUrlEncoder().withoutPadding().encodeToString`. */
    public fun encodeUrlSafeNoPadding(bytes: ByteArray): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

    private val basicDecoder = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    /**
     * Standard-alphabet decode — `Base64.getDecoder().decode`: padding is accepted
     * but not required; any other illegal character, a dangling single symbol or
     * data after the padding throws [IllegalArgumentException].
     */
    public fun decodeStrict(text: String): ByteArray = basicDecoder.decode(text)
}
