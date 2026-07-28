package com.artemchep.keyguard.common.service.settings

import com.artemchep.keyguard.common.service.settings.entity.LocalClaimedLicenseStateEntity
import com.artemchep.keyguard.common.service.settings.entity.LocalLicenseClaimFailureEntity
import com.artemchep.keyguard.common.service.settings.entity.LocalRedeemedLicenseStateEntity
import kotlinx.coroutines.flow.Flow

interface VaultSettingsReadRepository {
    fun getHibpApiToken(): Flow<String?>

    fun getRedeemedLicenseState(): Flow<LocalRedeemedLicenseStateEntity?>

    fun getClaimedLicenseState(): Flow<LocalClaimedLicenseStateEntity?>

    fun getLicenseClaimFailure(): Flow<LocalLicenseClaimFailureEntity?>
}
