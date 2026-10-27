package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.Premium
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.usecase.GetCachePremium
import com.artemchep.keyguard.common.usecase.GetDebugPremium
import com.artemchep.keyguard.common.usecase.GetLicensePremium
import com.artemchep.keyguard.common.usecase.PutCachePremium
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
class GetPurchasedImplTest {
    @Test
    fun licenseGrantsPremiumWhileStoreIsUnknown() = runTest {
        val fixture = Fixture(this)
        val values = fixture.collect()
        runCurrent()
        assertEquals(Premium.NONE, values.last())

        fixture.license.value = true
        runCurrent()
        assertEquals(Premium.PURCHASED, values.last())
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun oneShotReadWaitsForTheLicense() = runTest {
        val fixture = Fixture(this)
        fixture.license.value = null
        val premium = async { fixture.purchased().first() }
        runCurrent()
        assertFalse(premium.isCompleted)

        fixture.license.value = true
        runCurrent()
        assertEquals(Premium.PURCHASED, premium.await())
    }

    @Test
    fun licenseDoesNotPolluteThePurchaseCache() = runTest {
        val fixture = Fixture(this)
        val values = fixture.collect()
        fixture.license.value = true
        fixture.store.value = false
        runCurrent()
        assertEquals(Premium.PURCHASED, values.last())
        assertEquals(listOf(false), fixture.writes)

        fixture.license.value = false // vault lock, token removal, or expiry
        runCurrent()
        assertEquals(Premium.NONE, values.last())
        assertEquals(listOf(false), fixture.writes)
    }

    @Test
    fun unknownStoreKeepsCacheButVerifiedRevocationRemovesIt() = runTest {
        val fixture = Fixture(this, cached = true)
        val values = fixture.collect()
        runCurrent()
        assertEquals(Premium.PURCHASED, values.last())
        assertTrue(fixture.writes.isEmpty())

        fixture.store.value = false
        runCurrent()
        assertEquals(Premium.NONE, values.last())
        assertEquals(listOf(false), fixture.writes)
    }

    @Test
    fun purchaseIsCached() = runTest {
        val fixture = Fixture(this)
        val values = fixture.collect()
        fixture.store.value = true
        runCurrent()
        assertEquals(Premium.PURCHASED, values.last())
        assertEquals(listOf(true), fixture.writes)
    }

    @Test
    fun releaseIgnoresDebugPremium() = runTest {
        val fixture = Fixture(this, release = true)
        fixture.debug.value = true
        val values = fixture.collect()
        runCurrent()
        assertEquals(Premium.NONE, values.last())
    }

    @Test
    fun debugPremiumIsNotCached() = runTest {
        val fixture = Fixture(this)
        val values = fixture.collect()
        fixture.debug.value = true
        runCurrent()
        assertEquals(Premium.PURCHASED, values.last())

        fixture.debug.value = false
        runCurrent()
        assertEquals(Premium.NONE, values.last())
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
        assertTrue(values.all { it == Premium.PURCHASED })
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun freeFlavorIsAlwaysPremiumAndNeverCached() = runTest {
        val fixture = Fixture(this, free = true)
        val values = fixture.collect()
        runCurrent()
        assertEquals(listOf(Premium.INCLUDED), values)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun freeFlavorIgnoresTheStoreButNotLicenses() = runTest {
        val fixture = Fixture(this, cached = true, free = true)
        val values = fixture.collect()
        fixture.store.value = true
        runCurrent()
        assertEquals(Premium.INCLUDED, values.last())

        fixture.license.value = true
        runCurrent()
        assertEquals(Premium.PURCHASED, values.last())

        fixture.license.value = false
        runCurrent()
        assertEquals(Premium.INCLUDED, values.last())
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun includedPremiumIsReportedUntilSomethingIsPaid() = runTest {
        val fixture = Fixture(this, withoutPurchase = Premium.INCLUDED)
        val values = fixture.collect()
        runCurrent()
        assertEquals(Premium.INCLUDED, values.last())

        fixture.license.value = true
        runCurrent()
        assertEquals(Premium.PURCHASED, values.last())

        fixture.license.value = false
        runCurrent()
        assertEquals(Premium.INCLUDED, values.last())
        assertTrue(fixture.writes.isEmpty())
    }

    private class Fixture(
        private val scope: TestScope,
        cached: Boolean = false,
        release: Boolean = false,
        free: Boolean = false,
        withoutPurchase: Premium = Premium.NONE,
    ) {
        /** A store verdict, or `null` while the store is loading or failing. */
        val store = MutableStateFlow<Boolean?>(null)
        /** A license, or `null` while the license is not read yet. */
        val license = MutableStateFlow<Boolean?>(false)
        val debug = MutableStateFlow(false)
        val cache = MutableStateFlow(cached)
        val writes = mutableListOf<Boolean>()
        val purchased = GetPurchasedImpl(
            config = FlavorConfig(isFreeAsBeer = free),
            storePurchased = store.filterNotNull(),
            getLicensePremium = object : GetLicensePremium { override fun invoke() = license.filterNotNull() },
            getDebugPremium = object : GetDebugPremium { override fun invoke() = debug },
            getCachePremium = object : GetCachePremium { override fun invoke() = cache },
            putCachePremium = object : PutCachePremium {
                override fun invoke(premium: Boolean) = ioEffect { writes += premium; cache.value = premium }
            },
            windowCoroutineScope = object : WindowCoroutineScope, CoroutineScope by scope.backgroundScope {},
            withoutPurchase = withoutPurchase,
            release = release,
        )

        fun collect(): List<Premium> {
            val values = mutableListOf<Premium>()
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                purchased().toList(values)
            }
            return values
        }
    }
}
