package com.artemchep.keyguard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppleBillingProductInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val displayPrice: String,
    val isSubscription: Boolean,
    val subscriptionPeriodIso: String?,
    val introTrialPeriodIso: String?,
)

/** Only verified current entitlements may cross this boundary. */
class AppleBillingEntitlement(
    val productId: String,
    val originalTransactionId: String,
    val environment: String,
    val signedTransactionInfo: String,
    val willRenew: Boolean,
    val renewalKnown: Boolean,
)

enum class AppleBillingOutcome { SUCCESS, CANCELLED, PENDING, FAILURE }

class AppleBillingState(
    val products: List<AppleBillingProductInfo> = emptyList(),
    val catalogLoaded: Boolean = false,
    val catalogError: String? = null,
    val entitlements: List<AppleBillingEntitlement> = emptyList(),
    val entitlementsLoaded: Boolean = false,
    val entitlementError: String? = null,
    val busy: Boolean = false,
    val outcome: AppleBillingOutcome? = null,
    val message: String? = null,
) {
    companion object {
        val empty = AppleBillingState()
    }
}

interface AppleBillingObservation {
    fun cancel()
}

interface AppleBillingBridge {
    fun observeState(productIds: List<String>, onChange: (AppleBillingState) -> Unit): AppleBillingObservation
    fun purchase(productId: String, onResult: (AppleBillingOutcome) -> Unit)
    fun restore(onResult: (AppleBillingOutcome) -> Unit)
    fun manageSubscriptions()
    fun refresh()
}

object AppleBillingBridgeRegistry {
    private val mutableBridge = MutableStateFlow<AppleBillingBridge?>(null)
    val changes = mutableBridge.asStateFlow()
    var bridge: AppleBillingBridge?
        get() = mutableBridge.value
        set(value) { mutableBridge.value = value }
}

fun registerAppleBillingBridge(bridge: AppleBillingBridge) {
    AppleBillingBridgeRegistry.bridge = bridge
}
