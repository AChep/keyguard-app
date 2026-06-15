package com.artemchep.keyguard.common.service.similarity.impl

import com.artemchep.keyguard.common.service.similarity.SimilarityService
import com.artemchep.keyguard.common.service.similarity.util.JaroWinklerStrategy
import org.kodein.di.DirectDI

class SimilarityServiceImpl(
) : SimilarityService {
    constructor(
        directDI: DirectDI,
    ) : this()

    override fun score(
        a: String,
        b: String,
    ): Float = JaroWinklerStrategy.score(a, b).toFloat()
}
