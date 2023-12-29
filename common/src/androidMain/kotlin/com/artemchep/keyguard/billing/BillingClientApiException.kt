package com.artemchep.keyguard.billing

class BillingClientApiException(
    val reason: Int,
) : RuntimeException()
