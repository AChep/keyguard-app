package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.platform.LeContext

data class Subscription(
    val id: String,
    val title: String,
    val description: String?,
    val price: String,
    val status: Status,
    val purchase: (LeContext) -> Unit,
) {
    sealed interface Status {
        data class Inactive(
            val hasTrialAvailable: Boolean,
        ) : Status

        data class Active(
            val willRenew: Boolean,
        ) : Status
    }
}
