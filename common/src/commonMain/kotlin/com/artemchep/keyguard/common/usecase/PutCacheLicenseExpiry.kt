package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement

interface PutCacheLicenseExpiry : (LicenseEntitlement.Expiry?) -> IO<Unit>
