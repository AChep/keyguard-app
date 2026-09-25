package com.artemchep.keyguard.platform

import platform.LocalAuthentication.LAContext

/** Access to the biometric wrapping key using the context that authorized this operation. */
interface AppleBiometricKeychain {
    suspend fun putBiometric(id: String, value: String, context: LAContext)

    suspend fun getBiometric(id: String, context: LAContext): String
}
