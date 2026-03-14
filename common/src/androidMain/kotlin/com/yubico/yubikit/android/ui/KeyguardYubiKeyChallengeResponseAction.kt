package com.yubico.yubikit.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.artemchep.keyguard.feature.yubikey.ERROR_FAILED
import com.artemchep.keyguard.feature.yubikey.ERROR_SLOT_NOT_CONFIGURED
import com.artemchep.keyguard.feature.yubikey.ERROR_UNSUPPORTED
import com.artemchep.keyguard.feature.yubikey.EXTRA_CHALLENGE
import com.artemchep.keyguard.feature.yubikey.EXTRA_ERROR_MESSAGE
import com.artemchep.keyguard.feature.yubikey.EXTRA_ERROR_TYPE
import com.artemchep.keyguard.feature.yubikey.EXTRA_RESPONSE
import com.artemchep.keyguard.feature.yubikey.EXTRA_SLOT
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.application.CommandException
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.util.Callback
import com.yubico.yubikit.core.util.Pair
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.YubiOtpSession
import java.io.IOException

class KeyguardYubiKeyChallengeResponseAction : YubiKeyPromptAction() {
    override fun onYubiKey(
        device: YubiKeyDevice,
        extras: Bundle,
        commandState: CommandState,
        callback: Callback<Pair<Int, Intent>>,
    ) {
        val slot = extras.getInt(EXTRA_SLOT, 2)
        val challenge = extras.getByteArray(EXTRA_CHALLENGE)
            ?: return callback.invoke(failure(slot, ERROR_FAILED, "Missing challenge data."))
        YubiOtpSession.create(device) { result ->
            val session = try {
                result.getValue()
            } catch (e: Throwable) {
                callback.invoke(
                    when (e) {
                        is ApplicationNotAvailableException ->
                            failure(slot, ERROR_UNSUPPORTED, e.message)

                        else ->
                            failure(slot, ERROR_FAILED, e.message)
                    },
                )
                return@create
            }
            val pair = try {
                val slotId = slot.asYubiOtpSlot()
                val isConfigured = try {
                    session.configurationState.isConfigured(slotId)
                } catch (_: UnsupportedOperationException) {
                    true
                }
                if (!isConfigured) {
                    return@create callback.invoke(
                        failure(
                            slot,
                            ERROR_SLOT_NOT_CONFIGURED,
                            "Slot is not configured for challenge-response.",
                        ),
                    )
                }
                val response = session.calculateHmacSha1(
                    slotId,
                    challenge,
                    commandState,
                )
                Pair(
                    Activity.RESULT_OK,
                    Intent()
                        .putExtra(EXTRA_SLOT, slot)
                        .putExtra(EXTRA_RESPONSE, response),
                )
            } catch (e: Throwable) {
                when (e) {
                    is ApplicationNotAvailableException ->
                        failure(slot, ERROR_UNSUPPORTED, e.message)

                    is CommandException,
                    is IOException,
                        -> failure(slot, ERROR_FAILED, e.message)

                    else ->
                        failure(slot, ERROR_FAILED, e.message)
                }
            }
            callback.invoke(pair)
        }
    }
}

private fun failure(
    slot: Int,
    type: String,
    message: String?,
) = Pair(
    Activity.RESULT_CANCELED,
    Intent()
        .putExtra(EXTRA_SLOT, slot)
        .putExtra(EXTRA_ERROR_TYPE, type)
        .putExtra(EXTRA_ERROR_MESSAGE, message),
)

private fun Int.asYubiOtpSlot() = if (this == 1) Slot.ONE else Slot.TWO
