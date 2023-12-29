package com.artemchep.keyguard.copy

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient.SkuType
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.SkuDetailsParams
import com.artemchep.keyguard.android.closestActivityOrNull
import com.artemchep.keyguard.billing.BillingConnection
import com.artemchep.keyguard.billing.BillingManager
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.timeout
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.Product
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.model.Subscription
import com.artemchep.keyguard.common.model.combine
import com.artemchep.keyguard.common.model.map
import com.artemchep.keyguard.common.model.orNull
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import org.kodein.di.DirectDI
import org.kodein.di.instance

class SubscriptionServiceAndroid(
    private val billingManager: BillingManager,
) : SubscriptionService {
    companion object {
        private val SkuListSubscription = listOf(
            "premium",
            "premium_3m",
        )

        private val SkuListProduct = listOf(
            "premium_lifetime",
        )
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        billingManager = directDI.instance(),
    )

    override fun purchased(): Flow<RichResult<Boolean>> = getReceiptFlow()
        .map { receiptsResult ->
            receiptsResult.map { receipts ->
                receipts.any {
                    val isSubscription = it.skus.intersect(SkuListSubscription).isNotEmpty()
                    val isProduct = it.skus.intersect(SkuListProduct).isNotEmpty()
                    isSubscription || isProduct
                }
            }
        }

    override fun subscriptions(): Flow<List<Subscription>?> = combine(
        getReceiptFlow()
            .filter { it !is RichResult.Loading },
        getSkuDetailsFlow(SkuType.SUBS)
            .filter { it !is RichResult.Loading },
    ) { receiptsResult, skuDetailsResult ->
        val receipts = receiptsResult.orNull()
        val skuDetails = skuDetailsResult.orNull()

        skuDetails?.map {
            val status = kotlin.run {
                val skuReceipt = receipts?.firstOrNull { purchase -> it.sku in purchase.skus }
                    ?: return@run Subscription.Status.Inactive
                Subscription.Status.Active(
                    willRenew = skuReceipt.isAutoRenewing,
                )
            }
            Subscription(
                id = it.sku,
                title = it.title,
                description = it.description,
                price = it.price,
                status = status,
                purchase = { context ->
                    val activity = context.context.closestActivityOrNull
                        ?: return@Subscription
                    val params = BillingFlowParams.newBuilder()
                        .run {
                            val existingPurchase = receipts
                                ?.firstOrNull {
                                    SkuListSubscription.intersect(it.skus)
                                        .isNotEmpty()
                                }
                            if (existingPurchase != null && it.sku !in existingPurchase.skus) {
                                val params = BillingFlowParams.SubscriptionUpdateParams
                                    .newBuilder()
                                    .setOldSkuPurchaseToken(existingPurchase.purchaseToken)
                                    .build()
                                setSubscriptionUpdateParams(params)
                            } else {
                                this
                            }
                        }
                        .setSkuDetails(it)
                        .build()
                    purchase(activity, params)
                        .crashlyticsTap()
                        .attempt()
                        .launchIn(GlobalScope)
                },
            )
        }
    }

    override fun products(): Flow<List<Product>?> = combine(
        getReceiptFlow()
            .filter { it !is RichResult.Loading },
        getSkuDetailsFlow(SkuType.INAPP)
            .filter { it !is RichResult.Loading },
    ) { receiptsResult, skuDetailsResult ->
        val receipts = receiptsResult.orNull()
        val skuDetails = skuDetailsResult.orNull()

        skuDetails?.map {
            val status = kotlin.run {
                receipts?.firstOrNull { purchase -> it.sku in purchase.skus }
                    ?: return@run Product.Status.Inactive
                Product.Status.Active
            }
            Product(
                id = it.sku,
                title = it.title,
                description = it.description,
                price = it.price,
                status = status,
                purchase = { context ->
                    val activity = context.context.closestActivityOrNull
                        ?: return@Product
                    val params = BillingFlowParams.newBuilder()
                        .setSkuDetails(it)
                        .build()
                    purchase(activity, params)
                        .crashlyticsTap()
                        .attempt()
                        .launchIn(GlobalScope)
                },
            )
        }
    }

    private fun purchase(
        activity: Activity,
        params: BillingFlowParams,
    ): IO<Unit> = billingManager
        .billingConnectionFlow
        .flatMapLatest { connection ->
            connection
                .clientLiveFlow
                .mapNotNull { result ->
                    result.orNull()
                }
        }
        .toIO()
        .timeout(1000L)
        // Launch the purchase dialog using
        // obtained billing client.
        .effectMap(Dispatchers.Main) { client ->
            client.launchBillingFlow(activity, params)
        }

    private fun getReceiptFlow() = billingManager
        .billingConnectionFlow
        .flatMapLatest { connection ->
            val subscriptionsFlow = connection
                .purchasesFlow(SkuType.SUBS)
                .onEachAcknowledge(connection)
            val productsFlow = connection
                .purchasesFlow(SkuType.INAPP)
                .onEachAcknowledge(connection)
            combine(
                subscriptionsFlow,
                productsFlow,
            ) { subscriptions, products ->
                subscriptions.combine(products)
            }
        }

    private fun Flow<RichResult<List<Purchase>>>.onEachAcknowledge(
        connection: BillingConnection,
    ) = this
        .onEach {
            val purchases = it.orNull()
                ?: return@onEach
            purchases.acknowledge(connection)
        }

    private fun List<Purchase>.acknowledge(
        connection: BillingConnection,
    ) = this
        .filter { !it.isAcknowledged }
        .forEach { purchase ->
            val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            connection.acknowledgePurchase(acknowledgePurchaseParams)
        }

    private fun getSkuDetailsFlow(skuType: String) = billingManager
        .billingConnectionFlow
        .flatMapLatest { connection ->
            val skusList = when (skuType) {
                SkuType.SUBS -> SkuListSubscription
                SkuType.INAPP -> SkuListProduct
                else -> error("Unknown SKU type!")
            }

            val skuDetailsParams = SkuDetailsParams.newBuilder()
                .setType(skuType)
                .setSkusList(skusList)
                .build()
            connection.skuDetailsFlow(skuDetailsParams)
        }
}
