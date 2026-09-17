package com.artemchep.keyguard.common.service.similarity.impl

import com.artemchep.keyguard.common.service.similarity.SimilarityService
import com.artemchep.keyguard.common.service.similarity.util.JaroWinklerStrategy

class SimilarityServiceImpl : SimilarityService {
    override fun score(
        a: String,
        b: String,
    ): Float = JaroWinklerStrategy.score(a, b).toFloat()
}
