package com.artemchep.keyguard.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.artemchep.keyguard.platform.recordException

class BillingResponseException(
    val code: Int,
    val debugMessage: String? = null,
    val subResponseCode: Int = BillingClient.OnPurchasesUpdatedSubResponseCode.NO_APPLICABLE_SUB_RESPONSE_CODE,
) : RuntimeException(
    createBillingResponseMessage(
        code = code,
        debugMessage = debugMessage,
        subResponseCode = subResponseCode,
    ),
) {
    constructor(billingResult: BillingResult) : this(
        code = billingResult.responseCode,
        debugMessage = billingResult.debugMessage,
        subResponseCode = billingResult.onPurchasesUpdatedSubResponseCode,
    )
}

internal fun createBillingResponseMessage(
    code: Int,
    debugMessage: String?,
    subResponseCode: Int = BillingClient.OnPurchasesUpdatedSubResponseCode.NO_APPLICABLE_SUB_RESPONSE_CODE,
) = buildString {
    append("Google Play Billing failed with response code ")
    append(code)
    if (subResponseCode != BillingClient.OnPurchasesUpdatedSubResponseCode.NO_APPLICABLE_SUB_RESPONSE_CODE) {
        append(" and sub-response code ")
        append(subResponseCode)
    }
    if (!debugMessage.isNullOrBlank()) {
        append(": ")
        append(debugMessage)
    }
}

internal fun BillingResult.shouldRefreshPurchases() =
    when (responseCode) {
        BillingClient.BillingResponseCode.OK,
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
        -> true

        else -> false
    }

internal fun BillingResult.shouldReportPurchaseError() =
    !shouldRefreshPurchases() && responseCode != BillingClient.BillingResponseCode.USER_CANCELED

internal fun BillingResult.shouldRefreshPurchasesAfterLaunch() =
    responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED

internal fun BillingResult.throwIfPurchaseFailed() {
    if (shouldReportPurchaseError()) {
        throw BillingResponseException(this)
    }
}

internal fun BillingResult.recordIfPurchaseFailed() {
    if (shouldReportPurchaseError()) {
        val e = BillingResponseException(this)
        recordException(e)
    }
}

internal fun BillingResult.shouldRetryConnection() =
    when (responseCode) {
        BillingClient.BillingResponseCode.NETWORK_ERROR,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        -> true

        else -> false
    }

fun BillingResponseException.isNetworkIssue() =
    when (code) {
        BillingClient.BillingResponseCode.NETWORK_ERROR,
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
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
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
