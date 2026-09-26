package io.ucc.core.config

import io.ucc.core.platform.currentTimeMillis
import io.ucc.core.platform.randomUuidString

/** Injectable so tests get stable ids. */
public fun interface IdGenerator {
    public fun next(): String

    public companion object {
        public val Random: IdGenerator = IdGenerator { randomUuidString() }
    }
}

public fun interface TimeSource {
    public fun nowMs(): Long

    public companion object {
        public val System: TimeSource = TimeSource { currentTimeMillis() }
    }
}
