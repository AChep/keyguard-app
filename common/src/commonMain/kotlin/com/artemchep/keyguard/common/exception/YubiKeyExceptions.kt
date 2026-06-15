package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.feature.localization.TextHolder

class YubiKeyAuthCanceledException : RuntimeException()

class YubiKeySlotNotConfiguredException(
    slot: Int,
) : RuntimeException("YubiKey slot $slot is not configured for challenge-response."), Readable {
    override val title = TextHolder.Value("YubiKey slot is not configured")
    override val text = TextHolder.Value(
        "Provision slot $slot for Keyguard vault unlock again and try another unlock.",
    )
}

class YubiKeyUnsupportedException(
    message: String? = null,
) : RuntimeException(message ?: "This YubiKey does not support challenge-response unlock."), Readable {
    override val title = TextHolder.Value("YubiKey unlock is not supported")
    override val text = TextHolder.Value(
        "This YubiKey or connection type does not support challenge-response unlock.",
    )
}

class YubiKeySlotAccessCodeUnsupportedException(
    slot: Int,
    message: String? = null,
) : RuntimeException(
    message ?: "YubiKey slot $slot appears to require an access code that Keyguard does not support.",
), Readable {
    override val title = TextHolder.Value("Protected YubiKey slot is not supported")
    override val text = TextHolder.Value(
        "Slot $slot requires an access code or rejected the write. Configure it outside Keyguard and try again.",
    )
}

class YubiKeyProvisionWriteException(
    message: String? = null,
) : RuntimeException(message ?: "Failed to provision the YubiKey slot."), Readable {
    override val title = TextHolder.Value("Failed to provision YubiKey")
    override val text = TextHolder.Value(
        "Keyguard could not write or verify the selected slot. Your existing unlock setup was not changed.",
    )
}

class YubiKeyProvisionConfirmationRequiredException(
    slot: Int,
) : RuntimeException("YubiKey slot $slot requires overwrite confirmation."), Readable {
    override val title = TextHolder.Value("YubiKey slot is already configured")
    override val text = TextHolder.Value(
        "Confirm overwriting slot $slot before provisioning it for vault unlock.",
    )
}

class YubiKeyReadException(
    message: String? = null,
) : RuntimeException(message ?: "Failed to read a response from the YubiKey."), Readable {
    override val title = TextHolder.Value("Failed to use YubiKey")
    override val text = TextHolder.Value(
        "Try the YubiKey again or unlock the vault with your password.",
    )
}

class YubiKeyUnlockDecryptException(
    cause: Throwable,
) : RuntimeException("Failed to restore the vault key from the YubiKey protector.", cause), Readable {
    override val title = TextHolder.Value("Failed to use YubiKey")
    override val text = TextHolder.Value(
        "Try the YubiKey again or unlock the vault with your password.",
    )
}
