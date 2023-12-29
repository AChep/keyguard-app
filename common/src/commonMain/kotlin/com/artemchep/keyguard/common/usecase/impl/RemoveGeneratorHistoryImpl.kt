package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.android.downloader.journal.GeneratorHistoryRepository
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.usecase.RemoveGeneratorHistory
import org.kodein.di.DirectDI
import org.kodein.di.instance

class RemoveGeneratorHistoryImpl(
    private val generatorHistoryRepository: GeneratorHistoryRepository,
) : RemoveGeneratorHistory {
    constructor(directDI: DirectDI) : this(
        generatorHistoryRepository = directDI.instance(),
    )

    override fun invoke(): IO<Unit> = generatorHistoryRepository
        .removeAll()
}
