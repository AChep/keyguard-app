package com.artemchep.keyguard.apple.billing

import com.artemchep.keyguard.AppleBillingBridgeRegistry
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.Product
import com.artemchep.keyguard.common.model.Subscription
import com.artemchep.keyguard.common.usecase.GetProducts
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.GetSubscriptions
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.AppleBillingState
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.service.licensekey.LicenseManager
import com.artemchep.keyguard.common.service.licensekey.model.isCurrentlyLicensed
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.RedeemLicenseKey
import com.artemchep.keyguard.common.usecase.RemoveLicense
import com.artemchep.keyguard.common.usecase.SyncLicense
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import org.jetbrains.compose.resources.getString
import org.koin.core.scope.Scope

data class SubscriptionItemSnapshot(
    val id: String,
    val title: String,
    val description: String?,
    val price: String,
    val periodFormatted: String,
    /** This subscription is currently owned. */
    val active: Boolean,
    /** For an active subscription: whether it auto-renews. */
    val willRenew: Boolean,
    val renewalKnown: Boolean,
    /** Localized intro free-trial length when one is offered, else null. */
    val trialPeriodFormatted: String?,
)

/** One purchasable one-time product (lifetime) row in the paywall. */
data class ProductItemSnapshot(
    val id: String,
    val title: String,
    val description: String?,
    val price: String,
    /** This product is currently owned. */
    val active: Boolean,
)

/**
 * Combines the shared `GetSubscriptions` / `GetProducts` / `GetPurchased` use cases, the same ones the
 * Android/desktop Compose paywall consumes.
 */
data class SubscriptionsSnapshot(
    val loaded: Boolean,
    val isPremium: Boolean,
    val subscriptions: List<SubscriptionItemSnapshot>,
    val products: List<ProductItemSnapshot>,
    val billing: AppleBillingState = AppleBillingState.empty,
    val storeAvailable: Boolean = true,
    val license: AppleLicenseSnapshot = AppleLicenseSnapshot(),
) {
    companion object {
        val empty = SubscriptionsSnapshot(
            loaded = false,
            isPremium = false,
            subscriptions = emptyList(),
            products = emptyList(),
        )
    }
}

/** License data remains scoped to the unlocked vault, never to the billing singleton. */
data class AppleLicenseSnapshot(
    val unlocked: Boolean = false,
    val claimedKey: String? = null,
    val claimedStatus: String? = null,
    val linkedKey: String? = null,
    val linkedStatus: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class SubscriptionsController(private val ctx: CoreContext) {
    private val getSubscriptions: GetSubscriptions by lazy { ctx.koin.get() }
    private val getProducts: GetProducts by lazy { ctx.koin.get() }
    private val getPurchased: GetPurchased by lazy { ctx.koin.get() }
    private val getVaultSession: GetVaultSession by lazy { ctx.koin.get() }
    private val service: SubscriptionServiceApple by lazy {
        ctx.koin.get<SubscriptionService>() as SubscriptionServiceApple
    }
    private data class LicenseOperation(
        val session: MasterSession.Key? = null,
        val busy: Boolean = false,
        val message: String? = null,
    )
    private val licenseOperation = MutableStateFlow(LicenseOperation())
    private var premiumJob: Job? = null

    fun start() {
        if (premiumJob != null) return
        premiumJob = ctx.scope.launch { getPurchased().collect() }
    }

    private fun licenseFlow() = getVaultSession().flatMapLatest { session ->
        val key = session as? MasterSession.Key
            ?: return@flatMapLatest flowOf(AppleLicenseSnapshot())
        val manager: LicenseManager = key.sessionKoin.get()
        combine(manager.claimed, manager.redeemed, licenseOperation) { claimed, linked, operation ->
            AppleLicenseSnapshot(
                unlocked = true,
                claimedKey = claimed?.licenseKey,
                claimedStatus = claimed?.status?.name,
                linkedKey = linked?.licenseKey,
                linkedStatus = linked?.status?.name,
                busy = operation.busy && operation.session === key,
                message = operation.message.takeIf { operation.session === key },
            )
        }
    }

    fun observeSubscriptions(onChange: (SubscriptionsSnapshot) -> Unit): KeyguardCancellable = ctx.launchObserver {
        val base = combine(getSubscriptions(), getProducts(), getPurchased()) { subscriptions, products, premium ->
            Triple(subscriptions, products, premium)
        }
        combine(base, service.stateFlow, licenseFlow()) { data, billing, license ->
            val storeAvailable = !ctx.koin.get<FlavorConfig>().isFreeAsBeer
            SubscriptionsSnapshot(
                loaded = billing.catalogLoaded || !storeAvailable,
                isPremium = data.third,
                subscriptions = data.first?.map { subscription ->
                    subscription.toSnapshot(
                        billing.entitlements.firstOrNull { it.productId == subscription.id }?.renewalKnown == true,
                    )
                }.orEmpty(),
                products = data.second?.map { it.toSnapshot() }.orEmpty(),
                billing = billing,
                storeAvailable = storeAvailable,
                license = license,
            )
        }.collectOnMain { onChange(it) }
    }

    fun purchase(productId: String) { AppleBillingBridgeRegistry.bridge?.purchase(productId) { } }
    fun restorePurchases() { AppleBillingBridgeRegistry.bridge?.restore { } }
    fun manageSubscriptions() { AppleBillingBridgeRegistry.bridge?.manageSubscriptions() }
    fun refresh() { AppleBillingBridgeRegistry.bridge?.refresh() }

    fun syncLicense() = licenseAction { di ->
        when (di.get<SyncLicense>()().bind()) {
            is SyncLicense.Result.Synced -> getString(Res.string.pref_item_license_sync_success)
            is SyncLicense.Result.AlreadyLicensed -> getString(Res.string.pref_item_license_sync_already)
            SyncLicense.Result.NoPurchases -> getString(Res.string.pref_item_license_sync_no_purchases)
            else -> getString(Res.string.pref_item_license_sync_failed)
        }
    }

    fun linkLicense(value: String) = licenseAction { di ->
        val entitlement = di.get<RedeemLicenseKey>()(value.trim()).bind()
        getString(if (entitlement.isCurrentlyLicensed()) Res.string.pref_item_license_key_message_redeemed
            else Res.string.pref_item_license_key_message_invalid)
    }

    fun removeLicense() = licenseAction { di ->
        di.get<RemoveLicense>()().bind()
        getString(Res.string.pref_item_license_key_removed_text)
    }

    fun refreshLicense() = licenseAction { di ->
        // User-requested refresh bypasses the background staleness interval.
        di.get<LicenseManager>().refreshRedeemedIfNeeded(force = true).bind()
        getString(Res.string.pref_item_license_key_refreshed_text)
    }

    private fun licenseAction(block: suspend (Scope) -> String) {
        if (licenseOperation.value.busy) return
        licenseOperation.value = LicenseOperation(busy = true)
        ctx.scope.launch {
            val session = getVaultSession().first() as? MasterSession.Key
            if (session == null) {
                licenseOperation.value = LicenseOperation()
                return@launch
            }
            licenseOperation.value = LicenseOperation(session, busy = true)
            try {
                val message = coroutineScope {
                    val action = async { block(session.sessionKoin) }
                    val locked = async { getVaultSession().first { it !== session } }
                    try {
                        select<String?> {
                            action.onAwait { it }
                            locked.onAwait { null }
                        }
                    } finally {
                        action.cancel()
                        locked.cancel()
                    }
                }
                licenseOperation.value = LicenseOperation(session, message = message)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                licenseOperation.value = LicenseOperation(
                    session,
                    message = getString(Res.string.premium_purchase_failed_text),
                )
            } finally {
                licenseOperation.value = licenseOperation.value.copy(busy = false)
            }
        }
    }

    private fun Subscription.toSnapshot(renewalKnown: Boolean) = SubscriptionItemSnapshot(
        id = id, title = title, description = description, price = price,
        periodFormatted = periodFormatted, active = status is Subscription.Status.Active,
        willRenew = (status as? Subscription.Status.Active)?.willRenew ?: false,
        renewalKnown = renewalKnown,
        trialPeriodFormatted = (status as? Subscription.Status.Inactive)?.trialPeriodFormatted,
    )

    private fun Product.toSnapshot() = ProductItemSnapshot(
        id = id, title = title, description = description, price = price,
        active = status is Product.Status.Active,
    )
}
