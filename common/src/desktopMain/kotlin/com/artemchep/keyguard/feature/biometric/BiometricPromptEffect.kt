package com.artemchep.keyguard.feature.biometric

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.whenResumed
import arrow.core.left
import arrow.core.right
import com.artemchep.autotype.biometricsVerify
import com.artemchep.keyguard.common.model.BiometricAuthException
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_UNKNOWN
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.BiometricAuthPromptSimple
import com.artemchep.keyguard.common.model.PureBiometricAuthPrompt
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeBiometricCipherKeychain
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.ui.CollectedEffect
import kotlinx.coroutines.flow.Flow

@Composable
actual fun BiometricPromptEffect(flow: Flow<PureBiometricAuthPrompt>) {
    val context by rememberUpdatedState(LocalLeContext)
    val lifecycle by rememberUpdatedState(LocalLifecycleOwner.current)
    CollectedEffect(flow) { event ->
        // We want the screen to be visible and on front, when the biometric
        // prompt is popping up.
        lifecycle.lifecycle.whenResumed {
            when (event) {
                is BiometricAuthPrompt -> {
                    val title = textResource(event.title, context)
                    kotlin.runCatching {
                        biometricsVerify(
                            title = title,
                        )
                    }.fold(
                        onSuccess = {
                            val result = event.cipher
                                .also {
                                    val platformCipher = it as LeBiometricCipherKeychain
                                    platformCipher.materialize()
                                }
                                .right()
                            event.onComplete(result)
                        },
                        onFailure = {
                            val message = it.message.orEmpty()
                            val result = BiometricAuthException(ERROR_UNKNOWN, message)
                                .left()
                            event.onComplete(result)
                        },
                    )
                }

                is BiometricAuthPromptSimple -> {
                    val title = textResource(event.title, context)
                    kotlin.runCatching {
                        biometricsVerify(
                            title = title,
                        )
                    }.fold(
                        onSuccess = {
                            val result = Unit.right()
                            event.onComplete(result)
                        },
                        onFailure = {
                            val message = it.message.orEmpty()
                            val result = BiometricAuthException(ERROR_UNKNOWN, message)
                                .left()
                            event.onComplete(result)
                        },
                    )
                }
            }
        }
    }
}
