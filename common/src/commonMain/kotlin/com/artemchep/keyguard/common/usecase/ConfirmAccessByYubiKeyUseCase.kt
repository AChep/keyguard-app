package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface ConfirmAccessByYubiKeyUseCase : () -> IO<ConfirmAccessByYubiKeyRequest?>

data class ConfirmAccessByYubiKeyRequest(
    val slot: Int,
    val challenge: ByteArray,
    val confirm: (ByteArray) -> IO<Unit>,
)
