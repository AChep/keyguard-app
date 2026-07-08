package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysRequest
import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysResult

interface RefreshGpgPublicKeys : (RefreshGpgPublicKeysRequest) -> IO<RefreshGpgPublicKeysResult>
