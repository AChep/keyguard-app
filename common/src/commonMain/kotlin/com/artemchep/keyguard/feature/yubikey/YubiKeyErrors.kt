package com.artemchep.keyguard.feature.yubikey

import com.artemchep.keyguard.common.exception.YubiKeyAuthCanceledException
import com.artemchep.keyguard.common.exception.YubiKeyProvisionConfirmationRequiredException
import com.artemchep.keyguard.common.exception.YubiKeyReadException
import com.artemchep.keyguard.common.exception.YubiKeySlotAccessCodeUnsupportedException
import com.artemchep.keyguard.common.exception.YubiKeySlotNotConfiguredException
import com.artemchep.keyguard.common.exception.YubiKeyUnsupportedException
import com.artemchep.keyguard.util.yubikey.YubiKeyException
import com.artemchep.keyguard.util.yubikey.YubiKeyFailure
import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.yubikey_error_busy
import com.artemchep.keyguard.res.yubikey_error_multiple_devices
import com.artemchep.keyguard.res.yubikey_error_no_device
import com.artemchep.keyguard.res.yubikey_error_title
import org.jetbrains.compose.resources.StringResource

fun Throwable.asYubiKeyAppException(
    slot: Int,
    provisioning: Boolean = false,
): Throwable = when ((this as? YubiKeyException)?.failure) {
    YubiKeyFailure.CANCELED -> YubiKeyAuthCanceledException()
    YubiKeyFailure.UNSUPPORTED -> YubiKeyUnsupportedException()
    YubiKeyFailure.NOT_CONFIGURED -> YubiKeySlotNotConfiguredException(slot)
    YubiKeyFailure.CONFIRMATION_REQUIRED -> YubiKeyProvisionConfirmationRequiredException(slot)
    YubiKeyFailure.REJECTED -> if (provisioning) {
        YubiKeySlotAccessCodeUnsupportedException(slot)
    } else {
        YubiKeyReadException()
    }
    YubiKeyFailure.NO_DEVICE -> YubiKeyDeviceException(Res.string.yubikey_error_no_device)
    YubiKeyFailure.MULTIPLE_DEVICES -> YubiKeyDeviceException(Res.string.yubikey_error_multiple_devices)
    YubiKeyFailure.BUSY -> YubiKeyDeviceException(Res.string.yubikey_error_busy)
    null -> this
    else -> YubiKeyReadException()
}

private class YubiKeyDeviceException(
    message: StringResource,
) : RuntimeException("YubiKey device unavailable"), Readable {
    override val title = TextHolder.Res(Res.string.yubikey_error_title)
    override val text = TextHolder.Res(message)
}
