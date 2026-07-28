package com.artemchep.keyguard.common.service.settings

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.entity.LocalClaimedLicenseStateEntity
import com.artemchep.keyguard.common.service.settings.entity.LocalLicenseClaimFailureEntity
import com.artemchep.keyguard.common.service.settings.entity.LocalRedeemedLicenseStateEntity

interface VaultSettingsReadWriteRepository : VaultSettingsReadRepository {
    fun setHibpApiToken(
        token: String?,
    ): IO<Unit>

    fun setRedeemedLicenseState(
        state: LocalRedeemedLicenseStateEntity?,
    ): IO<Unit>

    fun setClaimedLicenseState(
        state: LocalClaimedLicenseStateEntity?,
    ): IO<Unit>

    fun setClaimedLicenseAttemptFailure(
        state: LocalLicenseClaimFailureEntity?,
    ): IO<Unit>
}
