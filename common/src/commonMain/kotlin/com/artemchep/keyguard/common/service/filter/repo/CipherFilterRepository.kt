package com.artemchep.keyguard.common.service.filter.repo

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.service.filter.model.AddCipherFilterRequest
import kotlinx.coroutines.flow.Flow

interface CipherFilterRepository {
    fun get(): Flow<List<DCipherFilter>>

    fun post(
        data: AddCipherFilterRequest,
    ): IO<Unit>

    fun patch(
        id: Long,
        name: String,
    ): IO<Unit>

    fun removeAll(): IO<Unit>

    fun removeByIds(
        ids: Set<Long>,
    ): IO<Unit>
}
