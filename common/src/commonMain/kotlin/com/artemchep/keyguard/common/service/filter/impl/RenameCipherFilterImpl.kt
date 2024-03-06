package com.artemchep.keyguard.common.service.filter.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.filter.RenameCipherFilter
import com.artemchep.keyguard.common.service.filter.model.RenameCipherFilterRequest
import com.artemchep.keyguard.common.service.filter.repo.CipherFilterRepository
import org.kodein.di.DirectDI
import org.kodein.di.instance

class RenameCipherFilterImpl(
    private val cipherFilterRepository: CipherFilterRepository,
) : RenameCipherFilter {
    constructor(directDI: DirectDI) : this(
        cipherFilterRepository = directDI.instance(),
    )

    override fun invoke(
        model: RenameCipherFilterRequest,
    ) = ioEffect {
        val name = model.name
        cipherFilterRepository
            .patch(
                id = model.id,
                name = name,
            )
            .bind()
    }
}
