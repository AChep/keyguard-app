package com.artemchep.keyguard.billing

import com.android.billingclient.api.BillingClient
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

class GooglePlayPurchasedTest {
    @Test
    fun `missing google play services grants premium`() = runTest {
        val verdicts = verdicts(
            RichResult.Success(false),
            hasGooglePlayServices = false,
        )
        assertEquals(listOf(true), verdicts)
    }

    @Test
    fun `purchases are verdicts`() = runTest {
        val verdicts = verdicts(
            RichResult.Success(false),
            RichResult.Success(true),
        )
        assertEquals(listOf(false, true), verdicts)
    }

    @Test
    fun `loading is not a verdict`() = runTest {
        val verdicts = verdicts(
            RichResult.Loading(),
        )
        assertEquals(emptyList(), verdicts)
    }

    @Test
    fun `unknown failures are not verdicts`() = runTest {
        val verdicts = verdicts(
            RichResult.Failure(RuntimeException()),
            RichResult.Failure(BillingResponseException(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)),
            RichResult.Failure(BillingResponseException(BillingClient.BillingResponseCode.NETWORK_ERROR)),
        )
        assertEquals(emptyList(), verdicts)
    }

    @Test
    fun `unsupported billing grants premium`() = runTest {
        val verdicts = verdicts(
            RichResult.Failure(BillingResponseException(BillingClient.BillingResponseCode.ERROR)),
            RichResult.Failure(BillingResponseException(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)),
            RichResult.Failure(BillingResponseException(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED)),
        )
        assertEquals(listOf(true, true, true), verdicts)
    }

    private suspend fun verdicts(
        vararg results: RichResult<Boolean>,
        hasGooglePlayServices: Boolean = true,
    ): List<Boolean> = object : SubscriptionService {
        override fun purchased() = flowOf(*results)
        override fun subscriptions(): Flow<List<Subscription>?> = flowOf(null)
        override fun products(): Flow<List<Product>?> = flowOf(null)
    }
        .googlePlayPurchased(hasGooglePlayServices = hasGooglePlayServices)
        .toList()
}
