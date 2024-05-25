package com.artemchep.keyguard.feature.biometric

import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.whenResumed
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.android.closestActivityOrNull
import com.artemchep.keyguard.common.model.BiometricAuthException
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.BiometricAuthPromptSimple
import com.artemchep.keyguard.common.model.PureBiometricAuthPrompt
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.ui.CollectedEffect
import kotlinx.coroutines.flow.Flow

@Composable
actual fun BiometricPromptEffect(
    flow: Flow<PureBiometricAuthPrompt>,
) {
    val context by rememberUpdatedState(LocalContext.current)
    val lifecycle by rememberUpdatedState(LocalLifecycleOwner.current)
    CollectedEffect(flow) { event ->
        // We want the screen to be visible and on front, when the biometric
        // prompt is popping up.
        lifecycle.lifecycle.whenResumed {
            val activity = context.closestActivityOrNull as FragmentActivity
            when (event) {
                is BiometricAuthPrompt -> activity.launchPrompt(event)
                is BiometricAuthPromptSimple -> activity.launchPrompt(event)
            }
        }
    }
}

private suspend fun FragmentActivity.launchPrompt(
    event: BiometricAuthPrompt,
) {
    val promptTitle = textResource(event.title, this)
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(promptTitle)
        .apply {
            event.text
                ?.let { textResource(it, this@launchPrompt) }
                // apply to the prompt
                ?.also(::setDescription)
        }
        .setNegativeButtonText(getString(android.R.string.cancel))
        .setConfirmationRequired(event.requireConfirmation)
        .build()
    val prompt = BiometricPrompt(
        this,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                val result = BiometricAuthException(
                    code = mapBiometricPromptErrorCode(errorCode),
                    message = errString.toString(),
                ).left()
                event.onComplete(result)
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val cipher = result.cryptoObject?.cipher
                    ?: return
                event.onComplete(cipher.right())
            }
        },
    )
    val crypto = BiometricPrompt.CryptoObject(event.cipher)
    prompt.authenticate(promptInfo, crypto)
}

private suspend fun FragmentActivity.launchPrompt(
    event: BiometricAuthPromptSimple,
) {
    val promptTitle = textResource(event.title, this)
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(promptTitle)
        .apply {
            event.text
                ?.let { textResource(it, this@launchPrompt) }
                // apply to the prompt
                ?.also(::setDescription)
        }
        .setNegativeButtonText(getString(android.R.string.cancel))
        .setConfirmationRequired(event.requireConfirmation)
        .build()
    val prompt = BiometricPrompt(
        this,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                val result = BiometricAuthException(
                    code = mapBiometricPromptErrorCode(errorCode),
                    message = errString.toString(),
                ).left()
                event.onComplete(result)
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                event.onComplete(Unit.right())
            }
        },
    )
    prompt.authenticate(promptInfo)
}

private fun mapBiometricPromptErrorCode(
    errorCode: Int,
) = when (errorCode) {
    // There is no error, and the user can
    // successfully authenticate.
    0 -> BiometricAuthException.BIOMETRIC_SUCCESS
    BiometricPrompt.ERROR_HW_UNAVAILABLE -> BiometricAuthException.ERROR_HW_UNAVAILABLE
    BiometricPrompt.ERROR_UNABLE_TO_PROCESS -> BiometricAuthException.ERROR_UNABLE_TO_PROCESS
    BiometricPrompt.ERROR_TIMEOUT -> BiometricAuthException.ERROR_TIMEOUT
    BiometricPrompt.ERROR_NO_SPACE -> BiometricAuthException.ERROR_NO_SPACE
    BiometricPrompt.ERROR_CANCELED -> BiometricAuthException.ERROR_CANCELED
    BiometricPrompt.ERROR_LOCKOUT -> BiometricAuthException.ERROR_LOCKOUT
    BiometricPrompt.ERROR_VENDOR -> BiometricAuthException.ERROR_VENDOR
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricAuthException.ERROR_LOCKOUT_PERMANENT
    BiometricPrompt.ERROR_USER_CANCELED -> BiometricAuthException.ERROR_USER_CANCELED
    BiometricPrompt.ERROR_NO_BIOMETRICS -> BiometricAuthException.ERROR_NO_BIOMETRICS
    BiometricPrompt.ERROR_HW_NOT_PRESENT -> BiometricAuthException.ERROR_HW_NOT_PRESENT
    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> BiometricAuthException.ERROR_NEGATIVE_BUTTON
    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> BiometricAuthException.ERROR_NO_DEVICE_CREDENTIAL
    BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED -> BiometricAuthException.ERROR_SECURITY_UPDATE_REQUIRED
    else -> BiometricAuthException.ERROR_UNKNOWN
}
