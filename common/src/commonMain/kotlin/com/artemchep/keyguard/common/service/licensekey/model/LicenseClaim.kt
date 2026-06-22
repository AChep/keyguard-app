package com.artemchep.keyguard.common.service.licensekey.model

/**
 * A store purchase to be validated by the license server in exchange for a
 * portable license key.
 *
 * @author Artem Chepurnyi
 */
sealed interface LicenseClaim {
    /**
     * A Google Play purchase.
     *
     * @param productType the raw Google Play product type, e.g. `subs` or
     * `inapp`; the server normalizes it.
     */
    data class Google(
        val purchaseToken: String,
        val productId: String,
        val productType: String,
    ) : LicenseClaim

    /** An Apple App Store transaction. */
    data class Apple(
        val signedTransactionInfo: String,
    ) : LicenseClaim
}