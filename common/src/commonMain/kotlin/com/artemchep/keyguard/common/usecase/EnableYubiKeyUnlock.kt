package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface EnableYubiKeyUnlock {
    operator fun invoke(
        slot: Int,
        challenge: ByteArray,
        response: ByteArray,
    ): IO<Unit>
}
