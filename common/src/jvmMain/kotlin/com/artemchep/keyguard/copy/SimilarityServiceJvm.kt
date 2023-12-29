package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.similarity.SimilarityService
import net.ricecode.similarity.JaroWinklerStrategy
import net.ricecode.similarity.SimilarityStrategy
import net.ricecode.similarity.StringSimilarityService
import net.ricecode.similarity.StringSimilarityServiceImpl
import org.kodein.di.DirectDI

class SimilarityServiceJvm(
) : SimilarityService {
    private val service: StringSimilarityService = kotlin.run {
        val strategy: SimilarityStrategy = JaroWinklerStrategy()
        StringSimilarityServiceImpl(strategy)
    }

    constructor(
        directDI: DirectDI,
    ) : this()

    override fun score(
        a: String,
        b: String,
    ): Float = service.score(a, b).toFloat()
}
