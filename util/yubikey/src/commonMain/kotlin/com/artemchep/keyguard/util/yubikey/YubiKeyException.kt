package com.artemchep.keyguard.util.yubikey

/** Stable native wire codes. Presentation and localization belong to the caller. */
@Suppress("MagicNumber") // These are the stable ABI codes, mirrored by the Rust error enum.
enum class YubiKeyFailure(val code: Int) {
    INVALID_ARGUMENT(1), UNSUPPORTED(2), NO_DEVICE(3), MULTIPLE_DEVICES(4),
    IO(5), NOT_CONFIGURED(6), CONFIRMATION_REQUIRED(7), REJECTED(8),
    TIMEOUT(9), CANCELED(10), PROTOCOL(11), BUSY(12), INTERNAL(13),
}

class YubiKeyException(
    val failure: YubiKeyFailure,
    cause: Throwable? = null,
) : RuntimeException("YubiKey operation failed: $failure", cause)
