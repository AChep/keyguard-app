package com.artemchep.keyguard.common.service.wordlist.repo

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DGeneratorWord
import com.artemchep.keyguard.provider.bitwarden.repository.BaseRepository
import kotlinx.coroutines.flow.Flow

interface GeneratorWordlistWordRepository : BaseRepository<DGeneratorWord> {
    fun getWords(
        wordlistId: Long,
    ): Flow<List<String>>

    fun removeAll(): IO<Unit>

    fun removeByIds(
        ids: Set<String>,
    ): IO<Unit>
}
