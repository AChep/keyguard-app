package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.GpgKeyserverVerifyStatus
import com.artemchep.keyguard.common.model.VerifyGpgPublicKeyRequest

interface VerifyGpgPublicKey : (
    VerifyGpgPublicKeyRequest,
) -> IO<GpgKeyserverVerifyStatus>
