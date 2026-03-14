package com.artemchep.keyguard.feature.yubikey

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.exception.YubiKeyAuthCanceledException
import com.artemchep.keyguard.common.exception.YubiKeyProvisionConfirmationRequiredException
import com.artemchep.keyguard.common.exception.YubiKeyProvisionTransportUnsupportedException
import com.artemchep.keyguard.common.exception.YubiKeyProvisionWriteException
import com.artemchep.keyguard.common.exception.YubiKeySlotAccessCodeUnsupportedException
import com.artemchep.keyguard.common.exception.YubiKeyUnsupportedException
import com.artemchep.keyguard.ui.CollectedEffect
import com.yubico.yubikit.android.ui.KeyguardYubiKeyInspectSlotAction
import com.yubico.yubikit.android.ui.KeyguardYubiKeyProvisionSlotAction
import com.yubico.yubikit.android.ui.YubiKeyPromptActivity
import kotlinx.coroutines.flow.Flow

internal data class YubiKeyProvisionProbePrompt(
    val slot: Int,
    val onComplete: (Either<Throwable, YubiKeyProvisionProbeResult>) -> Unit,
)

internal data class YubiKeyProvisionProbeResult(
    val slot: Int,
    val isConfigured: Boolean,
)

internal class YubiKeyProvisionPrompt(
    val slot: Int,
    val challenge: ByteArray,
    val secret: ByteArray,
    val overwrite: Boolean,
    val onComplete: (Either<Throwable, ByteArray>) -> Unit,
)

private data class YubiKeyProvisionProbeRequest(
    val slot: Int,
)

private data class YubiKeyProvisionRequest(
    val slot: Int,
    val challenge: ByteArray,
    val secret: ByteArray,
    val overwrite: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as YubiKeyProvisionRequest

        if (slot != other.slot) return false
        if (overwrite != other.overwrite) return false
        if (!challenge.contentEquals(other.challenge)) return false
        if (!secret.contentEquals(other.secret)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = slot
        result = 31 * result + overwrite.hashCode()
        result = 31 * result + challenge.contentHashCode()
        result = 31 * result + secret.contentHashCode()
        return result
    }
}

private class YubiKeyProvisionProbeContract :
    ActivityResultContract<YubiKeyProvisionProbeRequest, Either<Throwable, YubiKeyProvisionProbeResult>>() {
    override fun createIntent(
        context: Context,
        input: YubiKeyProvisionProbeRequest,
    ): Intent = YubiKeyPromptActivity
        .createIntent(
            context,
            KeyguardYubiKeyInspectSlotAction::class.java,
        )
        .putExtra(YubiKeyPromptActivity.ARG_ALLOW_NFC, false)
        .putExtra(EXTRA_SLOT, input.slot)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Either<Throwable, YubiKeyProvisionProbeResult> {
        val slot = intent?.getIntExtra(EXTRA_SLOT, 2) ?: 2
        val errorType = intent?.getStringExtra(EXTRA_ERROR_TYPE)
        val errorMessage = intent?.getStringExtra(EXTRA_ERROR_MESSAGE)
        return when {
            resultCode == Activity.RESULT_OK -> {
                YubiKeyProvisionProbeResult(
                    slot = slot,
                    isConfigured = intent?.getBooleanExtra(EXTRA_IS_CONFIGURED, true) ?: true,
                ).right()
            }

            errorType == ERROR_USB_ONLY -> {
                YubiKeyProvisionTransportUnsupportedException(errorMessage).left()
            }

            errorType == ERROR_UNSUPPORTED -> {
                YubiKeyUnsupportedException(errorMessage).left()
            }

            errorType == ERROR_FAILED -> {
                YubiKeyProvisionWriteException(errorMessage).left()
            }

            else -> {
                YubiKeyAuthCanceledException().left()
            }
        }
    }
}

private class YubiKeyProvisionContract :
    ActivityResultContract<YubiKeyProvisionRequest, Either<Throwable, ByteArray>>() {
    override fun createIntent(
        context: Context,
        input: YubiKeyProvisionRequest,
    ): Intent = YubiKeyPromptActivity
        .createIntent(
            context,
            KeyguardYubiKeyProvisionSlotAction::class.java,
        )
        .putExtra(YubiKeyPromptActivity.ARG_ALLOW_NFC, false)
        .putExtra(EXTRA_SLOT, input.slot)
        .putExtra(EXTRA_CHALLENGE, input.challenge)
        .putExtra(EXTRA_SECRET, input.secret)
        .putExtra(EXTRA_OVERWRITE, input.overwrite)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Either<Throwable, ByteArray> {
        val slot = intent?.getIntExtra(EXTRA_SLOT, 2) ?: 2
        val errorType = intent?.getStringExtra(EXTRA_ERROR_TYPE)
        val errorMessage = intent?.getStringExtra(EXTRA_ERROR_MESSAGE)
        return when {
            resultCode == Activity.RESULT_OK -> {
                val response = intent?.getByteArrayExtra(EXTRA_RESPONSE)
                    ?: return YubiKeyProvisionWriteException().left()
                response.right()
            }

            errorType == ERROR_OVERWRITE_CONFIRMATION_REQUIRED -> {
                YubiKeyProvisionConfirmationRequiredException(slot).left()
            }

            errorType == ERROR_PROTECTED_SLOT -> {
                YubiKeySlotAccessCodeUnsupportedException(slot, errorMessage).left()
            }

            errorType == ERROR_USB_ONLY -> {
                YubiKeyProvisionTransportUnsupportedException(errorMessage).left()
            }

            errorType == ERROR_UNSUPPORTED -> {
                YubiKeyUnsupportedException(errorMessage).left()
            }

            errorType == ERROR_FAILED -> {
                YubiKeyProvisionWriteException(errorMessage).left()
            }

            else -> {
                YubiKeyAuthCanceledException().left()
            }
        }
    }
}

@Composable
internal fun YubiKeyProvisionProbeEffect(
    flow: Flow<YubiKeyProvisionProbePrompt>,
) {
    val state = remember {
        mutableStateOf<YubiKeyProvisionProbePrompt?>(null)
    }
    val launcher = rememberLauncherForActivityResult(
        contract = remember { YubiKeyProvisionProbeContract() },
    ) { result ->
        state.value?.onComplete?.invoke(result)
        state.value = null
    }
    CollectedEffect(flow) { prompt ->
        state.value = prompt
        launcher.launch(
            YubiKeyProvisionProbeRequest(
                slot = prompt.slot,
            ),
        )
    }
}

@Composable
internal fun YubiKeyProvisionEffect(
    flow: Flow<YubiKeyProvisionPrompt>,
) {
    val state = remember {
        mutableStateOf<YubiKeyProvisionPrompt?>(null)
    }
    val launcher = rememberLauncherForActivityResult(
        contract = remember { YubiKeyProvisionContract() },
    ) { result ->
        state.value?.onComplete?.invoke(result)
        state.value = null
    }
    CollectedEffect(flow) { prompt ->
        state.value = prompt
        launcher.launch(
            YubiKeyProvisionRequest(
                slot = prompt.slot,
                challenge = prompt.challenge,
                secret = prompt.secret,
                overwrite = prompt.overwrite,
            ),
        )
    }
}

internal const val EXTRA_SECRET = "secret"
internal const val EXTRA_OVERWRITE = "overwrite"
internal const val EXTRA_IS_CONFIGURED = "is_configured"

internal const val ERROR_PROTECTED_SLOT = "protected_slot"
internal const val ERROR_USB_ONLY = "usb_only"
internal const val ERROR_OVERWRITE_CONFIRMATION_REQUIRED = "overwrite_confirmation_required"
