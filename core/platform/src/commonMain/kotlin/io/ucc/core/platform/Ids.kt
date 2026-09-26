package io.ucc.core.platform

/** Random version-4 UUID in canonical lower-case hyphenated form. */
public expect fun randomUuidString(): String

/** Wall clock, milliseconds since the Unix epoch. */
public expect fun currentTimeMillis(): Long
