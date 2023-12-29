package com.artemchep.keyguard.common.io

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun <T> IO<T>.launchIn(scope: CoroutineScope) = scope.launch { bind() }
