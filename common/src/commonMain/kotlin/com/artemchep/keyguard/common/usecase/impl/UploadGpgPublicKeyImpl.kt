package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.UploadGpgPublicKeyRequest
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.UploadGpgPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.kodein.di.DirectDI
import org.kodein.di.instance

class UploadGpgPublicKeyImpl(
    private val getCiphers: GetCiphers,
    private val getGpgKeyserverConfig: GetGpgKeyserverConfig,
    private val keyserverClient: GpgKeyserverClient,
) : UploadGpgPublicKey {
    constructor(
        directDI: DirectDI,
    ) : this(
        getCiphers = directDI.instance(),
        getGpgKeyserverConfig = directDI.instance(),
        keyserverClient = directDI.instance(),
    )

    override fun invoke(
        request: UploadGpgPublicKeyRequest,
    ): IO<Unit> = ioEffect(Dispatchers.Default) {
        val (_, publicKeyArmored) = getCiphers.requireGpgPublicKeyCipher(
            cipherId = request.cipherId,
            accountId = request.accountId,
        )

        val config = getGpgKeyserverConfig().first()
        keyserverClient.upload(
            publicKeyArmored = publicKeyArmored,
            config = config,
        ).bind()
    }
}
