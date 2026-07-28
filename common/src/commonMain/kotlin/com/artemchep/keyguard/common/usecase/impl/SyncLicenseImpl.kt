package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.service.licensekey.LicenseClaimSource
import com.artemchep.keyguard.common.service.licensekey.LicenseManager
import com.artemchep.keyguard.common.service.licensekey.model.isCurrentlyLicensed
import com.artemchep.keyguard.common.service.licensekey.model.selectBestLicenseClaimCandidate
import com.artemchep.keyguard.common.usecase.SyncLicense
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.kodein.di.DirectDI
import org.kodein.di.instance
import org.kodein.di.instanceOrNull

class SyncLicenseImpl(
    private val licenseManager: LicenseManager,
    private val licenseClaimSource: LicenseClaimSource?,
) : SyncLicense {
    constructor(directDI: DirectDI) : this(
        licenseManager = directDI.instance(),
        licenseClaimSource = directDI.instanceOrNull(),
    )

    override fun invoke(): IO<SyncLicense.Result> = ioEffect {
        val source = licenseClaimSource
            ?: return@ioEffect SyncLicense.Result.Unsupported
        val result = source.claims()
            .filter { it !is RichResult.Loading }
            .first()

        val candidates = when (result) {
            is RichResult.Failure -> throw result.exception
            is RichResult.Loading -> emptyList()
            is RichResult.Success -> result.data
        }
        val claim = candidates.selectBestLicenseClaimCandidate()
            ?: return@ioEffect SyncLicense.Result.NoPurchases
        val redeemed = licenseManager.redeemed
            .first()
        if (redeemed != null && redeemed.source == null && redeemed.isCurrentlyLicensed()) {
            return@ioEffect SyncLicense.Result.AlreadyLicensed(redeemed)
        }
        val entitlement = licenseManager
            .claim(
                claim = claim,
                force = true,
            )
            .bind()

        when {
            entitlement?.isCurrentlyLicensed() == true && entitlement.source == null ->
                SyncLicense.Result.AlreadyLicensed(entitlement)
            entitlement?.isCurrentlyLicensed() == true ->
                SyncLicense.Result.Synced(entitlement)
            else ->
                SyncLicense.Result.NotLicensed(entitlement)
        }
    }
}
