package com.artemchep.keyguard.common.usecase

fun interface Fido2UnlockAvailability {
    fun isSupported(): Boolean
}
