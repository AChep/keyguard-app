package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface GpgKeyPrivateExport : (GpgKeyPrivateExport.Request) -> IO<String?> {
    data class Request(
        val fingerprint: String,
        val privateKeyArmored: String,
    )
}
