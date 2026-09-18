package io.ucc.core.platform

import platform.Foundation.NSDate
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970

/** `NSUUID` emits upper-case hex; `java.util.UUID.toString()` is lower-case, so normalise. */
public actual fun randomUuidString(): String = NSUUID().UUIDString.lowercase()

/** `NSDate.timeIntervalSince1970` is seconds (Double); truncated to whole milliseconds like `System.currentTimeMillis()`. */
public actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
