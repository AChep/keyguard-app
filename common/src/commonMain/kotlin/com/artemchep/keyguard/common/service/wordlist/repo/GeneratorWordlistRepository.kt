package com.artemchep.keyguard.common.service.wordlist.repo

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DGeneratorWordlist
import kotlinx.coroutines.flow.Flow

interface GeneratorWordlistRepository {
    fun get(): Flow<List<DGeneratorWordlist>>

    fun post(
        name: String,
        wordlist: List<String>,
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
