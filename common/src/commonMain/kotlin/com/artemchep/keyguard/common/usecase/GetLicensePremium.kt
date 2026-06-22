package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow

/**
 * License redeeming: observes whether the redeemed entitlement grants premium.
 */
interface GetLicensePremium : () -> Flow<Boolean>
