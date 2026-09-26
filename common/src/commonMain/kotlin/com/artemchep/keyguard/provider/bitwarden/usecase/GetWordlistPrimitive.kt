package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistWordRepository
import com.artemchep.keyguard.common.usecase.GetWordlistPrimitive
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn

/**
 * @author Artem Chepurnyi
 */
class GetWordlistPrimitiveImpl(
    private val generatorWordlistWordRepository: GeneratorWordlistWordRepository,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetWordlistPrimitive {
    override fun invoke(
        wordlistId: Long,
    ): Flow<List<String>> = generatorWordlistWordRepository
        .getWords(
            wordlistId = wordlistId,
        )
        .flowOn(dispatcher)
}
