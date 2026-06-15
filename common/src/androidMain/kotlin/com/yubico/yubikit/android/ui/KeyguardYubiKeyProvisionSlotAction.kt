package com.yubico.yubikit.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.artemchep.keyguard.feature.yubikey.ERROR_FAILED
import com.artemchep.keyguard.feature.yubikey.ERROR_OVERWRITE_CONFIRMATION_REQUIRED
import com.artemchep.keyguard.feature.yubikey.ERROR_PROTECTED_SLOT
import com.artemchep.keyguard.feature.yubikey.ERROR_UNSUPPORTED
import com.artemchep.keyguard.feature.yubikey.EXTRA_CHALLENGE
import com.artemchep.keyguard.feature.yubikey.EXTRA_ERROR_MESSAGE
import com.artemchep.keyguard.feature.yubikey.EXTRA_ERROR_TYPE
import com.artemchep.keyguard.feature.yubikey.EXTRA_OVERWRITE
import com.artemchep.keyguard.feature.yubikey.EXTRA_RESPONSE
import com.artemchep.keyguard.feature.yubikey.EXTRA_SECRET
import com.artemchep.keyguard.feature.yubikey.EXTRA_SLOT
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.application.CommandException
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.util.Callback
import com.yubico.yubikit.core.otp.CommandRejectedException
import com.yubico.yubikit.core.util.Pair
import com.yubico.yubikit.yubiotp.HmacSha1SlotConfiguration
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.YubiOtpSession
import java.io.IOException

class KeyguardYubiKeyProvisionSlotAction : YubiKeyPromptAction() {
    override fun onYubiKey(
        device: YubiKeyDevice,
        extras: Bundle,
        commandState: CommandState,
        callback: Callback<Pair<Int, Intent>>,
    ) {
        val slot = extras.getInt(EXTRA_SLOT, 2)
        val challenge = extras.getByteArray(EXTRA_CHALLENGE)
            ?: return callback.invoke(failure(slot, ERROR_FAILED, "Missing challenge data."))
        val secret = extras.getByteArray(EXTRA_SECRET)
            ?: return callback.invoke(failure(slot, ERROR_FAILED, "Missing provisioning secret."))
        val overwrite = extras.getBoolean(EXTRA_OVERWRITE, false)
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
                val configured = try {
                    session.configurationState.isConfigured(slotId)
                } catch (_: UnsupportedOperationException) {
                    true
                }
                if (configured && !overwrite) {
                    return@create callback.invoke(
                        failure(slot, ERROR_OVERWRITE_CONFIRMATION_REQUIRED, "Slot is already configured."),
                    )
                }

                val configuration = HmacSha1SlotConfiguration(secret)
                    .requireTouch(true)
                    .lt64(true)
                session.putConfiguration(
                    slotId,
                    configuration,
                    null,
                    null,
                )
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
            } catch (e: CommandRejectedException) {
                failure(slot, ERROR_PROTECTED_SLOT, e.message)
            } catch (e: UnsupportedOperationException) {
                failure(slot, ERROR_UNSUPPORTED, e.message)
            } catch (e: CommandException) {
                failure(slot, ERROR_FAILED, e.message)
            } catch (e: IOException) {
                failure(slot, ERROR_FAILED, e.message)
            } catch (e: Throwable) {
                failure(slot, ERROR_FAILED, e.message)
            }
            callback.invoke(pair)
        }
    }
}

private fun Int.asYubiOtpSlot() = if (this == 1) Slot.ONE else Slot.TWO

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
