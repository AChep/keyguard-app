package com.artemchep.keyguard.feature.biometric

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.whenResumed
import arrow.core.left
import arrow.core.right
import com.artemchep.autotype.biometricsVerify
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.BiometricAuthPromptSimple
import com.artemchep.keyguard.common.model.PureBiometricAuthPrompt
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.ui.LocalComposeWindow
import com.artemchep.keyguard.ui.nativeWindowHandle
import kotlinx.coroutines.flow.Flow
import org.kodein.di.compose.rememberInstance

@Composable
actual fun BiometricPromptEffect(flow: Flow<PureBiometricAuthPrompt>) {
    val context by rememberUpdatedState(LocalLeContext)
    val lifecycle by rememberUpdatedState(LocalLifecycleOwner.current)
    val promptHost by rememberInstance<BiometricPromptHost>()
    val window = LocalComposeWindow.current
    val windowHandle = remember(window) {
        window.nativeWindowHandle ?: 0L
    }
    CollectedEffect(flow) { event ->
        // We want the screen to be visible and on front, when the biometric
        // prompt is popping up.
        lifecycle.lifecycle.whenResumed {
            when (event) {
                is BiometricAuthPrompt -> {
                    val request = BiometricPromptRequest(
                        title = textResource(event.title, context),
                        windowHandle = windowHandle,
                    )
                    kotlin.runCatching {
                        promptHost.materialize(
                            request = request,
                            cipher = event.cipher,
                        )
                    }.fold(
                        onSuccess = {
                            val result = event.cipher.right()
                            event.onComplete(result)
                        },
                        onFailure = {
                            val result = it.toBiometricAuthException()
                                .left()
                            event.onComplete(result)
                        },
                    )
                }

                is BiometricAuthPromptSimple -> {
                    val title = textResource(event.title, context)
                    kotlin.runCatching {
                        biometricsVerify(
                            windowHandle = windowHandle,
                            title = title,
                        )
                    }.fold(
                        onSuccess = {
                            val result = Unit.right()
                            event.onComplete(result)
                        },
                        onFailure = {
                            val result = it.toBiometricAuthException()
                                .left()
                            event.onComplete(result)
                        },
                    )
                }
            }
        }
    }
}
