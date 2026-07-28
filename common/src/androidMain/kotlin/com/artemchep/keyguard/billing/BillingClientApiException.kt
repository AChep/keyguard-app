package com.artemchep.keyguard.billing

import com.android.billingclient.api.BillingResult

class BillingClientApiException(
    val reason: Int,
    val debugMessage: String? = null,
) : RuntimeException(
    createBillingResponseMessage(
        code = reason,
        debugMessage = debugMessage,
    ),
) {
    constructor(billingResult: BillingResult) : this(
        reason = billingResult.responseCode,
        debugMessage = billingResult.debugMessage,
    )
}
