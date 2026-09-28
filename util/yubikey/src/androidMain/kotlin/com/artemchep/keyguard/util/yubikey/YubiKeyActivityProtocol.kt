package com.artemchep.keyguard.util.yubikey

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.yubico.yubikit.android.ui.KeyguardYubiKeyAction
import com.yubico.yubikit.android.ui.YubiKeyPromptActivity

/** Owns YubiKit's Intent protocol without depending on a particular UI framework. */
object YubiKeyActivityProtocol {
    fun createIntent(context: Context, input: YubiKeyOperation): Intent =
        YubiKeyPromptActivity.createIntent(context, KeyguardYubiKeyAction::class.java)
            .putExtra(EXTRA_SLOT, input.slot)
            .apply {
                when (input) {
                    is YubiKeyOperation.Inspect -> putExtra(EXTRA_OPERATION, OPERATION_INSPECT)
                    is YubiKeyOperation.ChallengeResponse -> {
                        putExtra(EXTRA_OPERATION, OPERATION_CHALLENGE_RESPONSE)
                        putExtra(EXTRA_CHALLENGE, input.challenge)
                    }
                    is YubiKeyOperation.Provision -> {
                        putExtra(EXTRA_OPERATION, OPERATION_PROVISION)
                        putExtra(EXTRA_CHALLENGE, input.challenge)
                        putExtra(EXTRA_SECRET, input.secret)
                        putExtra(EXTRA_OVERWRITE, input.overwrite)
                        putExtra(EXTRA_REQUIRE_TOUCH, input.requireTouch)
                    }
                }
            }

    fun parseResult(resultCode: Int, intent: Intent?): Result<YubiKeyResult> {
        if (resultCode != Activity.RESULT_OK || intent == null) {
            val code = intent?.getIntExtra(EXTRA_FAILURE, 0)
            val failure = YubiKeyFailure.entries.firstOrNull { it.code == code } ?: YubiKeyFailure.CANCELED
            return Result.failure(YubiKeyException(failure))
        }
        val response = intent.getByteArrayExtra(EXTRA_RESPONSE)
        return when {
            intent.hasExtra(EXTRA_IS_CONFIGURED) ->
                Result.success(YubiKeyResult.SlotStatus(intent.getBooleanExtra(EXTRA_IS_CONFIGURED, true)))
            response?.size == YUBIKEY_RESPONSE_LENGTH -> Result.success(YubiKeyResult.Response(response))
            else -> Result.failure(YubiKeyException(YubiKeyFailure.PROTOCOL))
        }
    }
}

/** Reads the extras written by [YubiKeyActivityProtocol.createIntent]. */
internal fun Bundle.toYubiKeyOperation(): YubiKeyOperation {
    val slot = getInt(EXTRA_SLOT, 2)
    val operation = when (getInt(EXTRA_OPERATION)) {
        OPERATION_INSPECT -> YubiKeyOperation.Inspect(slot)
        OPERATION_CHALLENGE_RESPONSE -> YubiKeyOperation.ChallengeResponse(
            slot,
            requireNotNull(getByteArray(EXTRA_CHALLENGE)),
        )
        OPERATION_PROVISION -> YubiKeyOperation.Provision(
            slot,
            requireNotNull(getByteArray(EXTRA_CHALLENGE)),
            requireNotNull(getByteArray(EXTRA_SECRET)),
            getBoolean(EXTRA_OVERWRITE, false),
            getBoolean(EXTRA_REQUIRE_TOUCH, true),
        )
        else -> null
    }
    return requireNotNull(operation)
}

private const val EXTRA_OPERATION = "operation"
private const val EXTRA_SLOT = "slot"
private const val EXTRA_CHALLENGE = "challenge"
private const val EXTRA_OVERWRITE = "overwrite"
private const val EXTRA_REQUIRE_TOUCH = "require_touch"
internal const val EXTRA_SECRET = "secret"
internal const val EXTRA_RESPONSE = "response"
internal const val EXTRA_IS_CONFIGURED = "is_configured"
internal const val EXTRA_FAILURE = "failure"
private const val OPERATION_INSPECT = 1
private const val OPERATION_CHALLENGE_RESPONSE = 2
private const val OPERATION_PROVISION = 3
