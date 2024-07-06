package com.artemchep.keyguard.billing

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.artemchep.keyguard.common.model.RichResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * @author Artem Chepurnyi
 */
interface BillingConnection {
    val clientLiveFlow: MutableStateFlow<RichResult<BillingClient>>

    /**
     * Perform a network query to get SKU details and
     * return the result asynchronously.
     */
    fun productDetailsFlow(params: QueryProductDetailsParams): Flow<RichResult<List<ProductDetails>>>

    /**
     * Get purchases details for all the items bought within your app.
     * This method uses a cache of Google Play Store app without initiating a network request.
     *
     * Note: It's recommended for security purposes to go through purchases verification
     * on your backend (if you have one) by calling one of the following APIs:
     * https://developers.google.com/android-publisher/api-ref/purchases/products/get
     * https://developers.google.com/android-publisher/api-ref/purchases/subscriptions/get
     */
    fun purchasesFlow(params: QueryPurchasesParams): Flow<RichResult<List<Purchase>>>

    fun launchBillingFlow(activity: Activity, billingFlowParams: BillingFlowParams)

    fun acknowledgePurchase(acknowledgePurchaseParams: AcknowledgePurchaseParams)
}
