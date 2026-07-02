package com.artemchep.keyguard.platform.input

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun IncognitoInput(
    content: @Composable () -> Unit,
) {
    InterceptPlatformTextInput(
        interceptor = NoInputMethodInterceptor,
        content = content,
    )
}

/**
 * Blocks input from starting an AWT input-method session for incognito fields. Compose keeps
 * normal physical key events separate from the platform text-input session.
 *
 * See:
 * https://github.com/AChep/keyguard-app/issues/1474
 * https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/swing/JPasswordField.html
 */
@OptIn(ExperimentalComposeUiApi::class)
private object NoInputMethodInterceptor : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing = awaitCancellation()
}
