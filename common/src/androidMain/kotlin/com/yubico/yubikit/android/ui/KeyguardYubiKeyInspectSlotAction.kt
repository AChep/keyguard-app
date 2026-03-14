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
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.otp.OtpConnection
import com.yubico.yubikit.core.util.Pair
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.YubiOtpSession
import java.io.IOException

class KeyguardYubiKeyInspectSlotAction : YubiKeyPromptConnectionAction<OtpConnection>(OtpConnection::class.java) {
    override fun onYubiKeyConnection(
        connection: OtpConnection,
        extras: Bundle,
        commandState: CommandState,
    ): Pair<Int, Intent> {
        val slot = extras.getInt(EXTRA_SLOT, 2)
        return try {
            val session = YubiOtpSession(connection)
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
