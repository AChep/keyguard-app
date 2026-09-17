package com.artemchep.keyguard.common.usecase

interface SendToolbox {
    val patchSendById: PatchSendById
    val removeSendById: RemoveSendById
}

class SendToolboxImpl(
    override val patchSendById: PatchSendById,
    override val removeSendById: RemoveSendById,
) : SendToolbox
