package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DGeneratorWordlist
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistRepository
import com.artemchep.keyguard.common.usecase.GetWordlists
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * @author Artem Chepurnyi
 */
class GetWordlistsImpl(
    private val generatorWordlistRepository: GeneratorWordlistRepository,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetWordlists {
    override fun invoke(): Flow<List<DGeneratorWordlist>> = generatorWordlistRepository
        .get()
        .map { list ->
            list
                .sorted()
        }
        .flowOn(dispatcher)
}
