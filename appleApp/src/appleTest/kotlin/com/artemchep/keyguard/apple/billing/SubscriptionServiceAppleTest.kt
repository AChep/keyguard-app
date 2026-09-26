package com.artemchep.keyguard.apple.billing

import com.artemchep.keyguard.AppleBillingBridge
import com.artemchep.keyguard.AppleBillingBridgeRegistry
import com.artemchep.keyguard.AppleBillingEntitlement
import com.artemchep.keyguard.AppleBillingObservation
import com.artemchep.keyguard.AppleBillingOutcome
import com.artemchep.keyguard.AppleBillingState
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.service.licensekey.model.selectBestLicenseClaimCandidate
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionServiceAppleTest {
    @AfterTest
    fun cleanup() { AppleBillingBridgeRegistry.bridge = null }

    @Test
    fun lateBridgeRegistrationResolvesEntitlementsWithoutCatalog() = runTest {
        val service = SubscriptionServiceApple(LeContext(), object : WindowCoroutineScope {
            override val coroutineContext = backgroundScope.coroutineContext
        }, NativeCryptoGenerator())
        val results = mutableListOf<RichResult<Boolean>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { service.purchased().toList(results) }
        runCurrent()
        assertIs<RichResult.Loading<Boolean>>(results.last())
        val bridge = FakeBridge()
        AppleBillingBridgeRegistry.bridge = bridge
        runCurrent()
        bridge.emit(listOf(entitlement("premium_lifetime")))
        runCurrent()
        assertEquals(RichResult.Success(true), results.last())
        bridge.emit(emptyList())
        runCurrent()
        assertEquals(RichResult.Success(false), results.last())
    }

    @Test
    fun claimsAreStableAcrossRenewalButSeparatedByEnvironmentAndProduct() {
        val crypto = NativeCryptoGenerator()
        val annual = assertNotNull(entitlement("premium_1y").toClaim(crypto))
        val renewed = assertNotNull(entitlement("premium_1y", jws = "renewed-jws").toClaim(crypto))
        val sandbox = assertNotNull(entitlement("premium_1y", environment = "Sandbox").toClaim(crypto))
        assertEquals(annual.source, renewed.source)
        assertNotEquals(annual.claim, renewed.claim)
        assertNotEquals(annual.source, sandbox.source)
        assertNull(entitlement("unknown").toClaim(crypto))
        assertNull(entitlement("premium_1y", jws = "").toClaim(crypto))
        val candidates = listOf(
            "premium_3m",
            "premium_1y",
            "premium_lifetime",
        ).mapNotNull { entitlement(it).toClaim(crypto) }
        assertEquals("premium_lifetime", candidates.selectBestLicenseClaimCandidate()?.source?.productId)
        assertEquals("premium_1y", candidates.take(2).selectBestLicenseClaimCandidate()?.source?.productId)
    }

    private fun entitlement(id: String, environment: String = "Production", jws: String = "signed-jws") =
        AppleBillingEntitlement(
            productId = id,
            originalTransactionId = "original-1",
            environment = environment,
            signedTransactionInfo = jws,
            willRenew = true,
            renewalKnown = true,
        )

    private class FakeBridge : AppleBillingBridge {
        private var callback: ((AppleBillingState) -> Unit)? = null
        override fun observeState(
            productIds: List<String>,
            onChange: (AppleBillingState) -> Unit,
        ): AppleBillingObservation {
            callback = onChange
            onChange(AppleBillingState.empty)
            return object : AppleBillingObservation { override fun cancel() { callback = null } }
        }
        fun emit(entitlements: List<AppleBillingEntitlement>) {
            callback?.invoke(AppleBillingState(entitlements = entitlements, entitlementsLoaded = true))
        }
        override fun purchase(productId: String, onResult: (AppleBillingOutcome) -> Unit) = Unit
        override fun restore(onResult: (AppleBillingOutcome) -> Unit) = Unit
        override fun manageSubscriptions() = Unit
        override fun refresh() = Unit
    }
}
