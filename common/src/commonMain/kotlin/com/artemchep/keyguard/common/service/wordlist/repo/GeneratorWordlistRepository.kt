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

    /**
     * Removes the words [remove] from the wordlist [id], then adds the
     * words [add], which the wordlist must not hold yet.
     */
    fun patchWords(
        id: Long,
        remove: Set<String>,
        add: List<String>,
    ): IO<Unit>

    fun removeAll(): IO<Unit>

    fun removeByIds(
        ids: Set<Long>,
    ): IO<Unit>
}
