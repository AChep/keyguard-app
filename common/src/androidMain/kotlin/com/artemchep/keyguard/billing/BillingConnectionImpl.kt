package com.artemchep.keyguard.billing

import android.app.Activity
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.SkuDetails
import com.android.billingclient.api.SkuDetailsParams
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.flattenMap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.retry
import com.artemchep.keyguard.common.io.timeout
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.model.flatMap
import com.artemchep.keyguard.common.model.orNull
import com.artemchep.keyguard.common.util.flow.EventFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val RETRY_DELAY = 1000L * 60L // 60s

/**
 * @author Artem Chepurnyi
 */
class BillingConnectionImpl(
    /**
     * The scope of the connection, it will be terminated after the scope
     * ends.
     */
    private val coroutineScope: CoroutineScope,
    private val dataChangedLiveFlow: EventFlow<Unit>,
    override val clientLiveFlow: MutableStateFlow<RichResult<BillingClient>>,
) : BillingConnection {
    companion object {
        private const val TAG = "BillingConnection"

        private const val TIMEOUT_LAUNCH_BILLING_FLOW = 1000L
    }

    override fun productDetailsFlow(params: QueryProductDetailsParams): Flow<RichResult<List<ProductDetails>>> =
        mapClient { client ->
            client.mapToSkuDetails(params)
        }

    private suspend fun BillingClient.mapToSkuDetails(params: QueryProductDetailsParams) =
        ioEffect(Dispatchers.IO) {
            querySkuDetailsSuspending(params)
        }
            .flattenMap()
            .flatMap { skuDetailsList ->
                skuDetailsList
                    ?.takeUnless { it.isEmpty() }
                    ?.let { io(it) }
                    ?: kotlin.run {
                        val exception =
                            IllegalArgumentException("Sku details list must not be null!")
                        ioRaise<List<ProductDetails>>(exception)
                    }
            }
            .retryIfNetworkIssue()
            .attempt().bind().let { RichResult.invoke(it) }

    override fun purchasesFlow(params: QueryPurchasesParams): Flow<RichResult<List<Purchase>>> =
        mapClient { client ->
            ioEffect(Dispatchers.IO) {
                client.queryPurchasesSuspending(params)
            }
                .flattenMap()
                .flatMap { purchasesList ->
                    purchasesList
                        ?.let { io(it) }
                        ?: kotlin.run {
                            val exception =
                                IllegalArgumentException("Purchase details list must not be null!")
                            ioRaise<List<Purchase>>(exception)
                        }
                }
                .retryIfNetworkIssue()
                .attempt().bind().let { RichResult.invoke(it) }
        }

    private fun <T> IO<T>.retryIfNetworkIssue() = this
        .retry { e, count ->
            if (e is BillingResponseException && e.isNetworkIssue()) {
                delay(RETRY_DELAY)
                true
            } else {
                false
            }
        }

    override fun launchBillingFlow(
        activity: Activity,
        billingFlowParams: BillingFlowParams,
    ) {
        coroutineScope.launch(Dispatchers.Main) {
            val client = getClient(TIMEOUT_LAUNCH_BILLING_FLOW) ?: return@launch
            client.launchBillingFlow(activity, billingFlowParams)
        }
    }

    override fun acknowledgePurchase(acknowledgePurchaseParams: AcknowledgePurchaseParams) {
        coroutineScope.launch(Dispatchers.IO) {
            val client = getClient(TIMEOUT_LAUNCH_BILLING_FLOW) ?: return@launch
            client.acknowledgePurchase(
                acknowledgePurchaseParams,
            ) { result ->
                // TODO: Do we want to verify the response?
                notifyDataChanged()
            }
        }
    }

    /** Returns a client within a timeout or returns null */
    private suspend fun getClient(timeout: Long) =
        ioEffect {
            clientLiveFlow
                .mapNotNull { it.orNull() }
                .firstOrNull()
        }
            .timeout(timeout)
            .attempt()
            .bind()
            .getOrNull()

    private fun notifyDataChanged() {
        dataChangedLiveFlow.emit(Unit)
    }

    private fun <T> mapClient(transform: suspend (BillingClient) -> RichResult<T>): Flow<RichResult<T>> =
        clientLiveFlow
            .flatMapLatest { clientResult ->
                dataChangedLiveFlow
                    .onStart {
                        emit(Unit)
                    }
                    .flatMapLatest {
                        flow {
                            emit(RichResult.Loading())
                            val result = clientResult
                                .flatMap {
                                    transform(it)
                                }
                            emit(result)
                        }
                    }
            }
}

private suspend fun BillingClient.querySkuDetailsSuspending(params: QueryProductDetailsParams) =
    suspendCancellableCoroutine<Either<BillingResponseException, List<ProductDetails>?>> { continuation ->
        queryProductDetailsAsync(params) { billingResult, skuDetailsList ->
            kotlin.runCatching {
                val r = getBillingResultOrException(billingResult, skuDetailsList)
                    .map { it.productDetailsList }
                continuation.resume(r)
            }
        }
    }

private suspend fun BillingClient.queryPurchasesSuspending(params: QueryPurchasesParams) =
    suspendCancellableCoroutine<Either<BillingResponseException, List<Purchase>?>> { continuation ->
        queryPurchasesAsync(params) { billingResult, purchases ->
            kotlin.runCatching {
                val r = getBillingResultOrException(billingResult, purchases)
                continuation.resume(r)
            }
        }
    }

private fun <T> getBillingResultOrException(billingResult: BillingResult, model: T) =
    when (val code = billingResult.responseCode) {
        BillingClient.BillingResponseCode.OK -> model.right()
        else -> BillingResponseException(code).left()
    }
