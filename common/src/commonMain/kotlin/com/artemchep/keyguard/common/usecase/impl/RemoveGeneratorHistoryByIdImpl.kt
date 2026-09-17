package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.android.downloader.journal.GeneratorHistoryRepository
import com.artemchep.keyguard.common.usecase.RemoveGeneratorHistoryById

class RemoveGeneratorHistoryByIdImpl(
    private val generatorHistoryRepository: GeneratorHistoryRepository,
) : RemoveGeneratorHistoryById {
    override fun invoke(ids: Set<String>) = generatorHistoryRepository
        .removeByIds(ids)
}
