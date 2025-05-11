package com.artemchep.keyguard.feature.navigation.keyboard

import androidx.compose.ui.input.key.KeyEvent
import com.artemchep.keyguard.feature.navigation.util.interceptEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

fun KeyEventInterceptorHost.interceptKeyEvent(
    scope: CoroutineScope,
    interceptorFlow: Flow<((KeyEvent) -> Boolean)?>,
) = interceptEvent(
    scope,
    interceptorFlow = interceptorFlow,
) { callbackProvider ->
    interceptKeyEvent { keyEvent ->
        val cb = callbackProvider()
            ?: return@interceptKeyEvent false
        cb.invoke(keyEvent)
    }
}
