package com.artemchep.keyguard.common.usecase

import org.kodein.di.DirectDI
import org.kodein.di.instance

interface SendToolbox {
    val patchSendById: PatchSendById
    val removeSendById: RemoveSendById
}

class SendToolboxImpl(
    override val patchSendById: PatchSendById,
    override val removeSendById: RemoveSendById,
) : SendToolbox {
    constructor(directDI: DirectDI) : this(
        patchSendById = directDI.instance(),
        removeSendById = directDI.instance(),
    )
}
