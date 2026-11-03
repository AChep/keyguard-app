package com.artemchep.keyguard.apple.billing

import com.artemchep.keyguard.common.model.orNull
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/** StoreKit verdicts: loading and failures are unknown and never grant premium. */
internal fun SubscriptionService.appleStorePurchased(): Flow<Boolean> =
    purchased().mapNotNull { it.orNull() }
