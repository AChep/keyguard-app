package com.artemchep.keyguard.common.io

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun <T : Any> Flow<T>.nullable(): Flow<T?> = map { it }
