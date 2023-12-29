package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance

class DeviceEncryptionKeyUseCase(
    private val cryptoGenerator: CryptoGenerator,
    private val deviceIdUseCase: DeviceIdUseCase,
) : () -> IO<ByteArray> {
    constructor(directDI: DirectDI) : this(
        cryptoGenerator = directDI.instance(),
        deviceIdUseCase = directDI.instance(),
    )

    override fun invoke() = deviceIdUseCase()
        .effectMap(Dispatchers.Default) { deviceId ->
            val seed = deviceId.toByteArray()
            cryptoGenerator.hkdf(seed = seed)
        }
}
