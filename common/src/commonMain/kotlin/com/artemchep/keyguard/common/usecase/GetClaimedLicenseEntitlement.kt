package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import kotlinx.coroutines.flow.Flow

/**
 * License claiming: observes the latest store-claimed entitlement.
 */
interface GetClaimedLicenseEntitlement : () -> Flow<LicenseEntitlement?>
