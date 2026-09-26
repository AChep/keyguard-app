package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.android.downloader.journal.GeneratorHistoryRepository
import com.artemchep.keyguard.common.usecase.GetGeneratorHistory

class GetGeneratorHistoryImpl(
    generatorHistoryRepository: GeneratorHistoryRepository,
) : GetGeneratorHistory {
    private val sharedFlow = generatorHistoryRepository.get()

    override fun invoke() = sharedFlow
}
