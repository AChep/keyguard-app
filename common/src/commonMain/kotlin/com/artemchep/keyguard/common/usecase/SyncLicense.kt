package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement

/**
 * License claiming: claims the best known platform purchase with the license service.
 */
interface SyncLicense : () -> IO<SyncLicense.Result> {
    sealed interface Result {
        data class Synced(
            val entitlement: LicenseEntitlement,
        ) : Result

        data class AlreadyLicensed(
            val entitlement: LicenseEntitlement,
        ) : Result

        data class NotLicensed(
            val entitlement: LicenseEntitlement?,
        ) : Result

        data object NoPurchases : Result

        data object Unsupported : Result
    }
}
