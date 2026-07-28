package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GpgKeyConfig

interface GpgKeyGenerator {
    fun generate(
        config: GpgKeyConfig,
    ): GeneratedGpgKey
}

object GpgKeyGeneratorUnsupported : GpgKeyGenerator {
    override fun generate(
        config: GpgKeyConfig,
    ): GeneratedGpgKey = throw UnsupportedOperationException(
        "GPG key generation is not supported on this platform.",
    )
}
