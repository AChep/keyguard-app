package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.platform.LeCipher

class WithBiometric(
    val getCipher: () -> LeCipher,
    val getCreateIo: () -> IO<Unit>,
)