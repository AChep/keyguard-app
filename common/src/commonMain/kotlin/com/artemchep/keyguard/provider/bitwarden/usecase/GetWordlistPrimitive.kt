package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistWordRepository
import com.artemchep.keyguard.common.usecase.GetWordlistPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext

/**
 * @author Artem Chepurnyi
 */
class GetWordlistPrimitiveImpl(
    private val generatorWordlistWordRepository: GeneratorWordlistWordRepository,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetWordlistPrimitive {
    constructor(directDI: DirectDI) : this(
        generatorWordlistWordRepository = directDI.instance(),
    )

    override fun invoke(
        wordlistId: Long,
    ): Flow<List<String>> = generatorWordlistWordRepository
        .getWords(
            wordlistId = wordlistId,
        )
        .flowOn(dispatcher)
}
