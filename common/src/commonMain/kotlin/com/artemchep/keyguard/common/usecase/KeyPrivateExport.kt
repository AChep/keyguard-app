package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.KeyPair

interface KeyPrivateExport : (KeyPair.KeyParameter) -> IO<String?>
