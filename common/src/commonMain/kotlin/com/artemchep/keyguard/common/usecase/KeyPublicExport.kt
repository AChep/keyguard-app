package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.KeyPair

interface KeyPublicExport : (KeyPair.KeyParameter) -> IO<String?>
