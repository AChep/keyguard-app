package com.artemchep.keyguard.common.service.filter.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.filter.RemoveCipherFilterById
import com.artemchep.keyguard.common.service.filter.repo.CipherFilterRepository
import org.kodein.di.DirectDI
import org.kodein.di.instance

class RemoveCipherFilterByIdImpl(
    private val cipherFilterRepository: CipherFilterRepository,
) : RemoveCipherFilterById {
    constructor(
        directDI: DirectDI,
    ) : this(
        cipherFilterRepository = directDI.instance(),
    )

    override fun invoke(
        ids: Set<Long>,
    ): IO<Unit> = cipherFilterRepository
        .removeByIds(ids)
}
