package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement

/**
 * License redeeming: refreshes the stored entitlement when it may be stale.
 */
interface RefreshLicense : () -> IO<LicenseEntitlement?>
