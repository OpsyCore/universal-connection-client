package io.ucc.core.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

public actual fun platformIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
