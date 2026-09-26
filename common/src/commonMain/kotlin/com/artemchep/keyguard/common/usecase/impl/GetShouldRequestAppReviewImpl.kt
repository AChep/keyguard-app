package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetCipherOpenedCount
import com.artemchep.keyguard.common.usecase.GetShouldRequestAppReview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GetShouldRequestAppReviewImpl(
    private val getCipherOpenedCount: GetCipherOpenedCount,
) : GetShouldRequestAppReview {
    // We should ask for the app review after a user opens or fills
    // a lot of the ciphers. Such a user is familiar enough with the
    // app to give it a full (and hopefully nice) review.
    override fun invoke(): Flow<Boolean> = getCipherOpenedCount()
        .map { it > 100 }
}
