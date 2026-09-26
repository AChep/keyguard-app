package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.service.urlblock.UrlBlockRepository
import com.artemchep.keyguard.common.usecase.AddUrlBlock

class AddUrlBlockImpl(
    private val urlBlockRepository: UrlBlockRepository,
) : AddUrlBlock {
    override fun invoke(model: DGlobalUrlBlock) = urlBlockRepository
        .put(model)
}
