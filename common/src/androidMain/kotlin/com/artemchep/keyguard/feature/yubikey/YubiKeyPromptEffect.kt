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
import com.artemchep.keyguard.common.exception.YubiKeyReadException
import com.artemchep.keyguard.common.exception.YubiKeySlotNotConfiguredException
import com.artemchep.keyguard.common.exception.YubiKeyUnsupportedException
import com.artemchep.keyguard.common.model.PureYubiKeyAuthPrompt
import com.artemchep.keyguard.common.model.YubiKeyAuthPrompt
import com.artemchep.keyguard.ui.CollectedEffect
import com.yubico.yubikit.android.ui.YubiKeyPromptActivity
import com.yubico.yubikit.android.ui.KeyguardYubiKeyChallengeResponseAction
import kotlinx.coroutines.flow.Flow

private class YubiKeyPromptRequest(
    val slot: Int,
    val challenge: ByteArray,
)

private class YubiKeyPromptContract :
    ActivityResultContract<YubiKeyPromptRequest, Either<Throwable, ByteArray>>() {
    override fun createIntent(
        context: Context,
        input: YubiKeyPromptRequest,
    ): Intent = YubiKeyPromptActivity
        .createIntent(
            context,
            KeyguardYubiKeyChallengeResponseAction::class.java,
        )
        .putExtra(EXTRA_SLOT, input.slot)
        .putExtra(EXTRA_CHALLENGE, input.challenge)

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
                    ?: return YubiKeyReadException().left()
                response.right()
            }

            errorType == ERROR_SLOT_NOT_CONFIGURED -> {
                YubiKeySlotNotConfiguredException(slot).left()
            }

            errorType == ERROR_UNSUPPORTED -> {
                YubiKeyUnsupportedException(errorMessage).left()
            }

            errorType == ERROR_FAILED -> {
                YubiKeyReadException(errorMessage).left()
            }

            else -> {
                YubiKeyAuthCanceledException().left()
            }
        }
    }
}

@Composable
actual fun YubiKeyPromptEffect(
    flow: Flow<PureYubiKeyAuthPrompt>,
) {
    val state = remember {
        mutableStateOf<YubiKeyAuthPrompt?>(null)
    }
    val launcher = rememberLauncherForActivityResult(
        contract = remember { YubiKeyPromptContract() },
    ) { result ->
        state.value?.onComplete?.invoke(result)
        state.value = null
    }
    CollectedEffect(flow) { event ->
        val prompt = event as? YubiKeyAuthPrompt ?: return@CollectedEffect
        state.value = prompt
        launcher.launch(
            YubiKeyPromptRequest(
                slot = prompt.slot,
                challenge = prompt.challenge,
            ),
        )
    }
}

internal const val EXTRA_SLOT = "slot"
internal const val EXTRA_CHALLENGE = "challenge"
internal const val EXTRA_RESPONSE = "response"
internal const val EXTRA_ERROR_TYPE = "error_type"
internal const val EXTRA_ERROR_MESSAGE = "error_message"

internal const val ERROR_SLOT_NOT_CONFIGURED = "slot_not_configured"
internal const val ERROR_UNSUPPORTED = "unsupported"
internal const val ERROR_FAILED = "failed"
