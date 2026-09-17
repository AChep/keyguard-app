package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import kotlinx.coroutines.Dispatchers

class DeviceEncryptionKeyUseCase(
    private val cryptoGenerator: CryptoGenerator,
    private val deviceIdUseCase: DeviceIdUseCase,
) : () -> IO<ByteArray> {
    override fun invoke() = deviceIdUseCase()
        .effectMap(Dispatchers.Default) { deviceId ->
            val seed = deviceId.encodeToByteArray()
            cryptoGenerator.hkdf(seed = seed)
        }
}
