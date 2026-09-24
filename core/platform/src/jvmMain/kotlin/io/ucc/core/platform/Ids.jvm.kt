package io.ucc.core.platform

import java.util.UUID

public actual fun randomUuidString(): String = UUID.randomUUID().toString()

public actual fun currentTimeMillis(): Long = System.currentTimeMillis()
