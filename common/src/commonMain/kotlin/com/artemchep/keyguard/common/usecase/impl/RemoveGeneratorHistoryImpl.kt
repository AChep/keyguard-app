package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.android.downloader.journal.GeneratorHistoryRepository
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.usecase.RemoveGeneratorHistory

class RemoveGeneratorHistoryImpl(
    private val generatorHistoryRepository: GeneratorHistoryRepository,
) : RemoveGeneratorHistory {
    override fun invoke(): IO<Unit> = generatorHistoryRepository
        .removeAll()
}
