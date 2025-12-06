package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.service.urlblock.UrlBlockRepository
import com.artemchep.keyguard.common.usecase.AddUrlBlock
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AddUrlBlockImpl(
    private val urlBlockRepository: UrlBlockRepository,
) : AddUrlBlock {
    constructor(directDI: DirectDI) : this(
        urlBlockRepository = directDI.instance(),
    )

    override fun invoke(model: DGlobalUrlBlock) = urlBlockRepository
        .put(model)
}
