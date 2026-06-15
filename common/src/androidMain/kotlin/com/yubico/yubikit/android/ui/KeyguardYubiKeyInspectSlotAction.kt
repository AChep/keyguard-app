package com.yubico.yubikit.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.artemchep.keyguard.feature.yubikey.ERROR_FAILED
import com.artemchep.keyguard.feature.yubikey.ERROR_UNSUPPORTED
import com.artemchep.keyguard.feature.yubikey.EXTRA_ERROR_MESSAGE
import com.artemchep.keyguard.feature.yubikey.EXTRA_ERROR_TYPE
import com.artemchep.keyguard.feature.yubikey.EXTRA_IS_CONFIGURED
import com.artemchep.keyguard.feature.yubikey.EXTRA_SLOT
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.util.Callback
import com.yubico.yubikit.core.util.Pair
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.YubiOtpSession
import java.io.IOException

class KeyguardYubiKeyInspectSlotAction : YubiKeyPromptAction() {
    override fun onYubiKey(
        device: YubiKeyDevice,
        extras: Bundle,
        commandState: CommandState,
        callback: Callback<Pair<Int, Intent>>,
    ) {
        val slot = extras.getInt(EXTRA_SLOT, 2)
        YubiOtpSession.create(device) { result ->
            val session = try {
                result.getValue()
            } catch (e: Throwable) {
                val failure = when (e) {
                    is ApplicationNotAvailableException ->
                        failure(
                            slot = slot,
                            type = ERROR_UNSUPPORTED,
                            message = e.message,
                        )

                    else -> failure(slot, ERROR_FAILED, e.message)
                }
                callback.invoke(failure)
                return@create
            }
            val pair = try {
                val slotId = slot.asYubiOtpSlot()
                val configured = try {
                    session.configurationState.isConfigured(slotId)
                } catch (_: UnsupportedOperationException) {
                    true
                }
                Pair(
                    Activity.RESULT_OK,
                    Intent()
                        .putExtra(EXTRA_SLOT, slot)
                        .putExtra(EXTRA_IS_CONFIGURED, configured),
                )
            } catch (e: UnsupportedOperationException) {
                failure(slot, ERROR_UNSUPPORTED, e.message)
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
