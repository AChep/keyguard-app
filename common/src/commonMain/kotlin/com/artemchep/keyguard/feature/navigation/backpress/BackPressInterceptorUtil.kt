package com.artemchep.keyguard.feature.navigation.backpress

import com.artemchep.keyguard.feature.navigation.util.interceptEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

fun BackPressInterceptorHost.interceptBackPress(
    scope: CoroutineScope,
    interceptorFlow: Flow<(() -> Unit)?>,
) = interceptEvent(
    scope,
    interceptorFlow = interceptorFlow,
) { callbackProvider ->
    interceptBackPress {
        val cb = callbackProvider()
            ?: return@interceptBackPress
        cb.invoke()
    }
}
