package com.artemchep.keyguard.common.service.filter.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.filter.AddCipherFilter
import com.artemchep.keyguard.common.service.filter.model.AddCipherFilterRequest
import com.artemchep.keyguard.common.service.filter.repo.CipherFilterRepository
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AddCipherFilterImpl(
    private val cipherFilterRepository: CipherFilterRepository,
) : AddCipherFilter {
    constructor(
        directDI: DirectDI,
    ) : this(
        cipherFilterRepository = directDI.instance(),
    )

    override fun invoke(
        request: AddCipherFilterRequest,
    ): IO<Unit> = cipherFilterRepository
        .post(data = request)
}
