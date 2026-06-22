package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement

/**
 * License redeeming: validates and stores a manually entered or store-issued key.
 */
interface RedeemLicenseKey : (String) -> IO<LicenseEntitlement>
