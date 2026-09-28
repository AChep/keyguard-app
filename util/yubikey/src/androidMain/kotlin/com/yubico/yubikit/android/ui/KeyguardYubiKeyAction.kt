package com.yubico.yubikit.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.artemchep.keyguard.util.yubikey.EXTRA_FAILURE
import com.artemchep.keyguard.util.yubikey.EXTRA_IS_CONFIGURED
import com.artemchep.keyguard.util.yubikey.EXTRA_RESPONSE
import com.artemchep.keyguard.util.yubikey.EXTRA_SECRET
import com.artemchep.keyguard.util.yubikey.YubiKeyException
import com.artemchep.keyguard.util.yubikey.YubiKeyFailure
import com.artemchep.keyguard.util.yubikey.YubiKeyOperation
import com.artemchep.keyguard.util.yubikey.YubiKeyResult
import com.artemchep.keyguard.util.yubikey.toYubiKeyOperation
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.otp.CommandRejectedException
import com.yubico.yubikit.core.util.Callback
import com.yubico.yubikit.core.util.Pair
import com.yubico.yubikit.yubiotp.HmacSha1SlotConfiguration
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.YubiOtpSession

/**
 * Callback boundary: every SDK outcome completes the activity and closes the connection.
 * Lives in YubiKit's package because [YubiKeyPromptAction.onYubiKey] is package-private.
 */
internal class KeyguardYubiKeyAction : YubiKeyPromptAction() {
    @Suppress("TooGenericExceptionCaught") // Translate SDK failures into the activity result protocol.
    override fun onYubiKey(
        device: YubiKeyDevice,
        extras: Bundle,
        commandState: CommandState,
        callback: Callback<Pair<Int, Intent>>,
    ) {
        val operation = try {
            extras.toYubiKeyOperation()
        } catch (_: IllegalArgumentException) {
            extras.getByteArray(EXTRA_SECRET)?.fill(0)
            callback.invoke(failure(YubiKeyFailure.IO))
            return
        }
        YubiOtpSession.create(device) { result ->
            val outcome = try {
                val response = result.getValue().use { session -> execute(session, operation, commandState) }
                val intent = when (response) {
                    is YubiKeyResult.SlotStatus -> Intent().putExtra(EXTRA_IS_CONFIGURED, response.configured)
                    is YubiKeyResult.Response -> Intent().putExtra(EXTRA_RESPONSE, response.bytes)
                }
                Pair(Activity.RESULT_OK, intent)
            } catch (error: Exception) {
                failure(failureOf(error, operation))
            } finally {
                (operation as? YubiKeyOperation.Provision)?.secret?.fill(0)
            }
            callback.invoke(outcome)
        }
    }
}

private fun execute(
    session: YubiOtpSession,
    operation: YubiKeyOperation,
    commandState: CommandState,
): YubiKeyResult {
    val slot = if (operation.slot == 1) Slot.ONE else Slot.TWO
    val configured = try {
        session.configurationState.isConfigured(slot)
    } catch (_: UnsupportedOperationException) {
        true // Unknown status always requires overwrite confirmation.
    }
    return when (operation) {
        is YubiKeyOperation.Inspect -> YubiKeyResult.SlotStatus(configured)
        is YubiKeyOperation.ChallengeResponse -> {
            if (!configured) throw YubiKeyException(YubiKeyFailure.NOT_CONFIGURED)
            YubiKeyResult.Response(session.calculateHmacSha1(slot, operation.challenge, commandState))
        }
        is YubiKeyOperation.Provision -> {
            if (configured && !operation.overwrite) {
                throw YubiKeyException(YubiKeyFailure.CONFIRMATION_REQUIRED)
            }
            val configuration = HmacSha1SlotConfiguration(operation.secret)
                .requireTouch(operation.requireTouch)
                .lt64(true)
            session.putConfiguration(slot, configuration, null, null)
            YubiKeyResult.Response(session.calculateHmacSha1(slot, operation.challenge, commandState))
        }
    }
}

private fun failureOf(error: Exception, operation: YubiKeyOperation): YubiKeyFailure = when (error) {
    is ApplicationNotAvailableException, is UnsupportedOperationException -> YubiKeyFailure.UNSUPPORTED
    is CommandRejectedException ->
        if (operation is YubiKeyOperation.Provision) YubiKeyFailure.REJECTED else YubiKeyFailure.IO
    is YubiKeyException -> error.failure
    else -> YubiKeyFailure.IO
}

private fun failure(failure: YubiKeyFailure) = Pair(
    Activity.RESULT_CANCELED,
    Intent().putExtra(EXTRA_FAILURE, failure.code),
)
