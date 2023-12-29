package com.artemchep.keyguard.common.service.similarity

interface SimilarityService {
    fun score(
        a: String,
        b: String,
    ): Float
}
