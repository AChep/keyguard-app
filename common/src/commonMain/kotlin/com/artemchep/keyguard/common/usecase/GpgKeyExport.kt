package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface GpgKeyExport : (GpgKeyExport.Request) -> IO<String?> {
    data class Request(
        val fingerprint: String,
        val publicKeyArmored: String,
        val privateKeyArmored: String,
    )
}
