package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest

interface SearchGpgPublicKey : (SearchGpgPublicKeyRequest) -> IO<List<DGpgKeyserverResult>>
