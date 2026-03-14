package com.artemchep.keyguard.common.usecase

fun interface YubiKeyUnlockAvailability {
    fun isSupported(): Boolean
}
