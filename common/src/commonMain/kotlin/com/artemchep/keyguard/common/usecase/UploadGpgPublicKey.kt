package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.UploadGpgPublicKeyRequest
import com.artemchep.keyguard.common.model.UploadGpgPublicKeyResult

interface UploadGpgPublicKey : (
    UploadGpgPublicKeyRequest,
) -> IO<UploadGpgPublicKeyResult>
