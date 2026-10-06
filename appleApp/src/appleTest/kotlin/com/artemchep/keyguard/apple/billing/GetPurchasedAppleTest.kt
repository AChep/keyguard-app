package com.artemchep.keyguard.apple.billing

import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.Product
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.model.Subscription
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.usecase.GetCachePremium
import com.artemchep.keyguard.common.usecase.GetDebugPremium
import com.artemchep.keyguard.common.usecase.GetLicensePremium
import com.artemchep.keyguard.common.usecase.PutCachePremium
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GetPurchasedAppleTest {
    @Test
    fun unknownAndFailedStoreKeepCacheButVerifiedRevocationRemovesIt() = runTest {
        val fixture = Fixture(this, cached = true)
        val values = fixture.collect()
        runCurrent()
        assertEquals(true, values.last())
        fixture.store.value = RichResult.Failure(Exception("offline"))
        runCurrent()
        assertEquals(true, values.last())
        assertTrue(fixture.writes.isEmpty())
        fixture.store.value = RichResult.Success(false)
        runCurrent()
        assertEquals(false, values.last())
        assertEquals(listOf(false), fixture.writes)
    }

    @Test
    fun linkedLicenseWorksWhileStoreIsLoadingAndDoesNotPollutePurchaseCache() = runTest {
        val fixture = Fixture(this)
        val values = fixture.collect()
        runCurrent()
        fixture.license.value = true
        runCurrent()
        assertEquals(true, values.last())
        fixture.store.value = RichResult.Success(false)
        runCurrent()
        assertEquals(true, values.last())
        assertEquals(listOf(false), fixture.writes)
        fixture.license.value = false // vault lock, token removal, or expiry
        runCurrent()
        assertEquals(false, values.last())
    }

    @Test
    fun releaseIgnoresDebugAndErrorDoesNotGrantPremium() = runTest {
        val fixture = Fixture(this, release = true)
        fixture.debug.value = true
        fixture.store.value = RichResult.Failure(Exception("StoreKit unavailable"))
        assertFalse(fixture.purchased().first())
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun directDistributionNeverCachesItsFreeGrant() = runTest {
        val fixture = Fixture(this, free = true)
        assertTrue(fixture.purchased().first())
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun debugOverrideGrantsAndRevokesPremiumWithoutCachingIt() = runTest {
        val fixture = Fixture(this)
        val values = fixture.collect()
        runCurrent()
        assertFalse(values.last())

        fixture.debug.value = true
        runCurrent()
        assertTrue(values.last())

        fixture.debug.value = false
        runCurrent()
        assertFalse(values.last())
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun debugGrantDoesNotEraseCachedPurchase() = runTest {
        val fixture = Fixture(this, cached = true)
        val values = fixture.collect()
        runCurrent()
        fixture.debug.value = true
        runCurrent()
        fixture.debug.value = false
        runCurrent()
        assertTrue(values.all { it })
        assertTrue(fixture.writes.isEmpty())
    }

    private class Fixture(
        private val scope: TestScope,
        cached: Boolean = false,
        release: Boolean = false,
        free: Boolean = false,
    ) {
        val store = MutableStateFlow<RichResult<Boolean>>(RichResult.Loading())
        val license = MutableStateFlow(false)
        val debug = MutableStateFlow(false)
        val cache = MutableStateFlow(cached)
        val writes = mutableListOf<Boolean>()
        val purchased = GetPurchasedApple(
            config = FlavorConfig(isFreeAsBeer = free),
            subscriptionService = object : SubscriptionService {
                override fun purchased() = store
                override fun subscriptions(): Flow<List<Subscription>?> = flowOf(null)
                override fun products(): Flow<List<Product>?> = flowOf(null)
            },
            getDebugPremium = object : GetDebugPremium { override fun invoke() = debug },
            getCachePremium = object : GetCachePremium { override fun invoke() = cache },
            putCachePremium = object : PutCachePremium {
                override fun invoke(premium: Boolean) = ioEffect { writes += premium; cache.value = premium }
            },
            getLicensePremium = object : GetLicensePremium { override fun invoke() = license },
            windowCoroutineScope = object : WindowCoroutineScope {
                override val coroutineContext = scope.backgroundScope.coroutineContext
            },
            release = release,
        )

        fun collect(): List<Boolean> {
            val values = mutableListOf<Boolean>()
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                purchased().toList(values)
            }
            return values
        }
    }
}
