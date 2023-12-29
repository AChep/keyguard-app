package com.artemchep.keyguard.billing

import com.android.billingclient.api.BillingClient

class BillingResponseException(
    val code: Int,
) : RuntimeException()

fun BillingResponseException.isNetworkIssue() =
    when (code) {
        BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        -> true

        else -> false
    }

/**
 * The requested feature is not supported by
 * the Play Store on the current device.
 */
fun BillingResponseException.isNotSupported() =
    when (code) {
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
        -> true

        else -> false
    }

/**
 * Fatal error during the API action.
 */
fun BillingResponseException.isFatalError() =
    when (code) {
        BillingClient.BillingResponseCode.ERROR,
        -> true

        else -> false
    }
