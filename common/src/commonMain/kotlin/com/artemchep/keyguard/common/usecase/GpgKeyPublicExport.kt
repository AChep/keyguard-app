package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface GpgKeyPublicExport : (GpgKeyPublicExport.Request) -> IO<String?> {
    data class Request(
        val fingerprint: String,
        val publicKeyArmored: String,
    )
}
