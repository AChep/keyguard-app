package com.artemchep.keyguard.apple.billing

import com.artemchep.keyguard.AppleBillingBridgeRegistry
import com.artemchep.keyguard.AppleBillingEntitlement
import com.artemchep.keyguard.AppleBillingProductInfo
import com.artemchep.keyguard.AppleBillingState
import com.artemchep.keyguard.common.model.DurationSimple
import com.artemchep.keyguard.common.model.Product
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.model.Subscription
import com.artemchep.keyguard.common.model.map
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.licensekey.LicenseClaimSource
import com.artemchep.keyguard.common.service.licensekey.model.LicenseClaim
import com.artemchep.keyguard.common.service.licensekey.model.LicenseClaimCandidate
import com.artemchep.keyguard.common.service.licensekey.model.LicenseSource
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn

/** Maps the process-wide StoreKit state to the shared billing and license APIs. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionServiceApple(
    private val context: LeContext,
    windowCoroutineScope: WindowCoroutineScope,
    private val cryptoGenerator: CryptoGenerator,
) : SubscriptionService, LicenseClaimSource {
    internal val stateFlow = AppleBillingBridgeRegistry.changes.flatMapLatest { bridge ->
        if (bridge == null) flowOf(AppleBillingState.empty) else callbackFlow {
            val observation = bridge.observeState(AppleBillingProducts.ALL_IDS) { trySend(it) }
            awaitClose { observation.cancel() }
        }
    }.shareIn(windowCoroutineScope, SharingStarted.WhileSubscribed(5_000L), replay = 1)

    override fun purchased(): Flow<RichResult<Boolean>> = stateFlow.map { state ->
        state.entitlementsResult().map { entitlements ->
            entitlements.any { it.productId in AppleBillingProducts.ALL_IDS }
        }
    }.onStart { emit(RichResult.Loading()) }

    override fun subscriptions(): Flow<List<Subscription>?> = stateFlow.map { state ->
        if (!state.catalogLoaded) return@map null
        AppleBillingProducts.SUBSCRIPTION_IDS
            .mapNotNull { id -> state.products.find { it.id == id && it.isSubscription } }
            .map { it.toSubscription(state.entitlements) }
    }

    override fun products(): Flow<List<Product>?> = stateFlow.map { state ->
        if (!state.catalogLoaded) return@map null
        AppleBillingProducts.PRODUCT_IDS
            .mapNotNull { id -> state.products.find { it.id == id && !it.isSubscription } }
            .map { it.toProduct(state.entitlements) }
    }

    override fun claims(): Flow<RichResult<List<LicenseClaimCandidate>>> = stateFlow.map { state ->
        state.entitlementsResult().map { entitlements ->
            entitlements.mapNotNull { it.toClaim(cryptoGenerator) }
        }
    }

    private fun AppleBillingState.entitlementsResult(): RichResult<List<AppleBillingEntitlement>> = when {
        entitlementError != null -> RichResult.Failure(IllegalStateException(entitlementError))
        !entitlementsLoaded -> RichResult.Loading()
        else -> RichResult.Success(entitlements)
    }

    private suspend fun AppleBillingProductInfo.toSubscription(
        entitlements: List<AppleBillingEntitlement>,
    ): Subscription {
        val period = DurationSimple.parse(subscriptionPeriodIso.orEmpty())
        val periodFormatted = period.format(context)
        val entitlement = entitlements.firstOrNull { it.productId == id }
        val status = if (entitlement != null) {
            Subscription.Status.Active(
                willRenew = entitlement.willRenew,
            )
        } else {
            val trialPeriod = introTrialPeriodIso?.let { DurationSimple.parse(it) }
            val trialPeriodFormatted = trialPeriod?.format(context)
            Subscription.Status.Inactive(
                trialPeriod = trialPeriod,
                trialPeriodFormatted = trialPeriodFormatted,
            )
        }
        return Subscription(
            id = id,
            title = displayName,
            description = description,
            price = displayPrice,
            status = status,
            period = period,
            periodFormatted = periodFormatted,
            // The native SwiftUI paywall purchases via KeyguardCore → the bridge
            // directly; this callback keeps the model self-contained (and is what
            // a shared Compose paywall would use). Fire-and-forget, like Android.
            purchase = { AppleBillingBridgeRegistry.bridge?.purchase(id) { } },
        )
    }

    private fun AppleBillingProductInfo.toProduct(entitlements: List<AppleBillingEntitlement>): Product {
        val status = if (entitlements.any { it.productId == id }) {
            Product.Status.Active
        } else {
            Product.Status.Inactive
        }
        return Product(
            id = id,
            title = displayName,
            description = description,
            price = displayPrice,
            status = status,
            purchase = { AppleBillingBridgeRegistry.bridge?.purchase(id) { } },
        )
    }
}

internal fun AppleBillingEntitlement.toClaim(
    cryptoGenerator: CryptoGenerator,
): LicenseClaimCandidate? {
    if (productId !in AppleBillingProducts.ALL_IDS || signedTransactionInfo.isBlank()) return null
    val productType = if (productId in AppleBillingProducts.PRODUCT_IDS) "inapp" else "subscription"
    val identity = listOf(LicenseSource.PROVIDER_APPLE, environment, productType, productId, originalTransactionId)
        .joinToString(":")
    return LicenseClaimCandidate(
        claim = LicenseClaim.Apple(signedTransactionInfo),
        source = LicenseSource(
            provider = LicenseSource.PROVIDER_APPLE,
            productId = productId,
            productType = productType,
            purchaseTokenHash = cryptoGenerator.hashSha256(identity.encodeToByteArray()).toHex(),
        ),
        priority = AppleBillingProducts.licenseClaimPriority(productId),
    )
}
