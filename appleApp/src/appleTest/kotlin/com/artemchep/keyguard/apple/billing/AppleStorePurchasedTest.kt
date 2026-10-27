package com.artemchep.keyguard.apple.billing

import com.artemchep.keyguard.common.model.Product
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.model.Subscription
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppleStorePurchasedTest {
    @Test
    fun purchasesAreVerdicts() = runTest {
        val verdicts = verdicts(
            RichResult.Success(true),
            RichResult.Success(false),
        )
        assertEquals(listOf(true, false), verdicts)
    }

    @Test
    fun loadingAndFailuresAreNotVerdicts() = runTest {
        val verdicts = verdicts(
            RichResult.Loading(),
            RichResult.Failure(Exception("offline")),
        )
        assertEquals(emptyList(), verdicts)
    }

    private suspend fun verdicts(
        vararg results: RichResult<Boolean>,
    ): List<Boolean> = object : SubscriptionService {
        override fun purchased() = flowOf(*results)
        override fun subscriptions(): Flow<List<Subscription>?> = flowOf(null)
        override fun products(): Flow<List<Product>?> = flowOf(null)
    }
        .appleStorePurchased()
        .toList()
}
