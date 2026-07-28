package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import kotlinx.coroutines.flow.Flow

/**
 * License redeeming: observes the manually redeemed entitlement.
 */
interface GetLicenseEntitlement : () -> Flow<LicenseEntitlement?>
