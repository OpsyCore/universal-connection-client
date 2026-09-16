package io.ucc.core.config

import java.util.UUID

/** Injectable so tests get stable ids. */
public fun interface IdGenerator {
    public fun next(): String

    public companion object {
        public val Random: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
    }
}

public fun interface TimeSource {
    public fun nowMs(): Long

    public companion object {
        public val System: TimeSource = TimeSource { java.lang.System.currentTimeMillis() }
    }
}
