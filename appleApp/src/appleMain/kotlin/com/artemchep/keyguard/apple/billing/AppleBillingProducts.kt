package com.artemchep.keyguard.apple.billing

/**
 * The single source of truth for Keyguard's Apple in-app purchase identifiers.
 * These must match the product ids configured in App Store Connect (and in the
 * local `Keyguard.storekit` test config).
 *
 * Mirrors the Android `GooglePlayBillingCatalog`, but the subscription ids
 * differ on purpose: Apple sells a 3-month + 1-year pair, while Google
 * currently sells `premium` + `premium_3m`. The stores are independent, so
 * the ids don't have to line up — owning any of these grants premium.
 */
object AppleBillingProducts {
    const val ID_SUB_3_MONTHS = "premium_3m"
    const val ID_SUB_1_YEAR = "premium_1y"

    const val ID_PROD_LIFETIME = "premium_lifetime"

    /** Auto-renewable subscriptions, all in one App Store subscription group. */
    val SUBSCRIPTION_IDS = listOf(
        ID_SUB_3_MONTHS,
        ID_SUB_1_YEAR,
    )

    /** Non-consumable one-time purchases (lifetime premium). */
    val PRODUCT_IDS = listOf(
        ID_PROD_LIFETIME,
    )

    /** Every purchasable id; passed to the Swift bridge to fetch from StoreKit. */
    val ALL_IDS = SUBSCRIPTION_IDS + PRODUCT_IDS

    private const val PRIORITY_PROD_LIFETIME = 0
    private const val PRIORITY_SUB_1_YEAR = 10
    private const val PRIORITY_SUB_3_MONTHS = 20

    // Lower is higher priority
    fun licenseClaimPriority(productId: String): Int = when (productId) {
        ID_PROD_LIFETIME -> PRIORITY_PROD_LIFETIME
        ID_SUB_1_YEAR -> PRIORITY_SUB_1_YEAR
        ID_SUB_3_MONTHS -> PRIORITY_SUB_3_MONTHS
        else -> Int.MAX_VALUE
    }
}
