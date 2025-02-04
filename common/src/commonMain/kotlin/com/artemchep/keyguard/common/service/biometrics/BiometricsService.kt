package com.artemchep.keyguard.common.service.biometrics

interface BiometricsService {
    fun isSupported(): Boolean

    suspend fun confirm(): Boolean
}
