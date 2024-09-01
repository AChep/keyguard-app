package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.platform.LeContext

data class Subscription(
    val id: String,
    val title: String,
    val description: String?,
    val price: String,
    val status: Status,
    val period: DurationSimple,
    val periodFormatted: String,
    val purchase: (LeContext) -> Unit,
) {
    sealed interface Status {
        data class Inactive(
            val trialPeriod: DurationSimple?,
            val trialPeriodFormatted: String?,
        ) : Status

        data class Active(
            val willRenew: Boolean,
        ) : Status
    }
}
