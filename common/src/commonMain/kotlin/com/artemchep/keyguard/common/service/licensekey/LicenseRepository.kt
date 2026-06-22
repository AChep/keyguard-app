package com.artemchep.keyguard.common.service.licensekey

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.licensekey.entity.LicenseEntitlementEntity
import com.artemchep.keyguard.common.service.licensekey.model.LicenseClaim

/**
 * @author Artem Chepurnyi
 */
interface LicenseRepository {
    /**
     * Validates a store purchase and obtains a license key.
     * Hits `/v1/license/claim/{provider}`.
     */
    fun claim(claim: LicenseClaim): IO<LicenseEntitlementEntity>

    /**
     * Refreshes the entitlement for an existing license key via
     * `/v1/license/status`.
     */
    fun status(licenseKey: String): IO<LicenseEntitlementEntity>
}