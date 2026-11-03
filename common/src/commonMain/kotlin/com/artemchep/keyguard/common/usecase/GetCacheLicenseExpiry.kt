package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import kotlinx.coroutines.flow.Flow

/**
 * The expiry of the redeemed license, kept outside of the vault
 * so it is known while the vault is locked.
 */
interface GetCacheLicenseExpiry : () -> Flow<LicenseEntitlement.Expiry?>
