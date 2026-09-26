package com.artemchep.keyguard.common.service.filter.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.filter.RemoveCipherFilterById
import com.artemchep.keyguard.common.service.filter.repo.CipherFilterRepository

class RemoveCipherFilterByIdImpl(
    private val cipherFilterRepository: CipherFilterRepository,
) : RemoveCipherFilterById {
    override fun invoke(
        ids: Set<Long>,
    ): IO<Unit> = cipherFilterRepository
        .removeByIds(ids)
}
