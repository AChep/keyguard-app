package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.service.urlblock.UrlBlockRepository
import com.artemchep.keyguard.common.usecase.RemoveUrlBlockById
import com.artemchep.keyguard.common.usecase.RemoveUrlOverrideById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RemoveUrlBlockByIdImpl(
    private val urlBlockRepository: UrlBlockRepository,
) : RemoveUrlBlockById {
    constructor(directDI: DirectDI) : this(
        urlBlockRepository = directDI.instance(),
    )

    override fun invoke(
        urlBlockIds: Set<String>,
    ): IO<Unit> = performRemoveUrlBlock(
        urlBlockIds = urlBlockIds,
    ).map { Unit }

    private fun performRemoveUrlBlock(
        urlBlockIds: Set<String>,
    ) = urlBlockRepository
        .removeByIds(urlBlockIds)
}
