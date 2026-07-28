package com.artemchep.keyguard.common.service.settings.impl

import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.getSerializable
import com.artemchep.keyguard.common.service.settings.VaultSettingsReadWriteRepository
import com.artemchep.keyguard.common.service.settings.entity.LocalClaimedLicenseStateEntity
import com.artemchep.keyguard.common.service.settings.entity.LocalLicenseClaimFailureEntity
import com.artemchep.keyguard.common.service.settings.entity.LocalRedeemedLicenseStateEntity
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

class VaultSettingsRepositoryImpl(
    private val store: VaultSettingsKeyValueStore,
    private val json: Json,
) : VaultSettingsReadWriteRepository {
    companion object {
        private const val KEY_HIBP_API_TOKEN = "hibp.api_token"

        private const val KEY_REDEEMED_LICENSE_STATE = "license.redeemed.state"
        private const val KEY_CLAIMED_LICENSE_STATE = "license.claimed.state"
        private const val KEY_LICENSE_CLAIM_FAILURE = "license.claim.failure"
    }

    constructor(directDI: DirectDI) : this(
        store = directDI.instance(),
        json = directDI.instance(),
    )

    private val hibpApiTokenPref =
        store.getString(KEY_HIBP_API_TOKEN, "")

    private val redeemedLicenseStatePref =
        store.getSerializable<LocalRedeemedLicenseStateEntity?>(
            json = json,
            key = KEY_REDEEMED_LICENSE_STATE,
            defaultValue = null,
        )

    private val claimedLicenseStatePref =
        store.getSerializable<LocalClaimedLicenseStateEntity?>(
            json = json,
            key = KEY_CLAIMED_LICENSE_STATE,
            defaultValue = null,
        )

    private val licenseClaimFailurePref =
        store.getSerializable<LocalLicenseClaimFailureEntity?>(
            json = json,
            key = KEY_LICENSE_CLAIM_FAILURE,
            defaultValue = null,
        )

    override fun setHibpApiToken(
        token: String?,
    ) = hibpApiTokenPref
        .setAndCommit(token?.trim().orEmpty())

    override fun getHibpApiToken() = hibpApiTokenPref
        .map { token ->
            token.takeUnless { it.isEmpty() }
        }

    override fun setRedeemedLicenseState(
        state: LocalRedeemedLicenseStateEntity?,
    ) = redeemedLicenseStatePref
        .setAndCommit(state)

    override fun getRedeemedLicenseState() = redeemedLicenseStatePref

    override fun setClaimedLicenseState(
        state: LocalClaimedLicenseStateEntity?,
    ) = claimedLicenseStatePref
        .setAndCommit(state)

    override fun getClaimedLicenseState() = claimedLicenseStatePref

    override fun setClaimedLicenseAttemptFailure(
        state: LocalLicenseClaimFailureEntity?,
    ) = licenseClaimFailurePref
        .setAndCommit(state)

    override fun getLicenseClaimFailure() = licenseClaimFailurePref
}
