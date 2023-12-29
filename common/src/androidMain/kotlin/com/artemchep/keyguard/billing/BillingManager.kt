package com.artemchep.keyguard.billing

import kotlinx.coroutines.flow.Flow

/**
 * @author Artem Chepurnyi
 */
interface BillingManager {
    val billingConnectionFlow: Flow<BillingConnection>
}
