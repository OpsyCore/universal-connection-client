package io.ucc.core.platform

import kotlinx.coroutines.CoroutineDispatcher

/** Dispatcher for blocking IO (sockets, files). `Dispatchers.IO` on JVM and Native. */
public expect fun platformIoDispatcher(): CoroutineDispatcher
