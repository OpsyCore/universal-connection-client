package io.ucc.core.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** kotlinx-coroutines ships `Dispatchers.IO` for Kotlin/Native (since 1.7); no custom thread pool needed. */
public actual fun platformIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
