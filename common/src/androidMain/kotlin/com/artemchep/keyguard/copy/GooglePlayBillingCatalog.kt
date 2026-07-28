package com.artemchep.keyguard.copy

import com.android.billingclient.api.BillingClient.ProductType

object GooglePlayBillingCatalog {
    const val ID_SUB_1_YEARS = "premium"
    const val ID_SUB_3_MONTHS = "premium_3m"

    const val ID_PROD_LIFETIME = "premium_lifetime"

    val subscriptionProductIds = setOf(
        ID_SUB_1_YEARS,
        ID_SUB_3_MONTHS,
    )

    val lifetimeProductIds = setOf(
        ID_PROD_LIFETIME,
    )

    // Lower is higher priority
    fun licenseClaimPriority(
        productId: String,
        @ProductType productType: String,
    ): Int = when (productType) {
        ProductType.INAPP if productId == ID_PROD_LIFETIME -> 0
        ProductType.SUBS if productId == ID_SUB_1_YEARS -> 10
        ProductType.SUBS if productId == ID_SUB_3_MONTHS -> 20
        else -> Int.MAX_VALUE
    }
}
