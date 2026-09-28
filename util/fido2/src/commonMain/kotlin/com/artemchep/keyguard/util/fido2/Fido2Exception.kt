@file:Suppress("MagicNumber") // Stable status codes in the native ABI.

package com.artemchep.keyguard.util.fido2

enum class Fido2Failure(val code: Int) {
    INVALID_ARGUMENT(1),
    UNSUPPORTED(2),
    PIN_REQUIRED(3),
    INVALID_PIN(4),
    PIN_BLOCKED(5),
    PIN_NOT_SET(6),
    TIMEOUT(7),
    CANCELED(8),
    PROTOCOL(9),
    BUSY(10),
    INTERNAL(11),
    REJECTED(12),
}

class Fido2Exception(val failure: Fido2Failure, cause: Throwable? = null) :
    RuntimeException("FIDO2 operation failed: ${failure.name}", cause)
