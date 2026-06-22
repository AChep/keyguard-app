package com.artemchep.keyguard.common.service.licensekey.impl

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.model.orNull
import com.artemchep.keyguard.common.service.licensekey.LicenseClaimSource
import com.artemchep.keyguard.common.service.licensekey.model.selectBestLicenseClaimCandidate
import com.artemchep.keyguard.common.service.licensekey.LicenseManager
import com.artemchep.keyguard.common.usecase.RefreshLicense
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.kodein.di.DirectDI
import org.kodein.di.instance
import org.kodein.di.instanceOrNull

class LicenseSyncer(
    private val licenseManager: LicenseManager,
    private val refreshLicense: RefreshLicense,
    private val licenseClaimSource: LicenseClaimSource?,
    private val licenseAutoClaim: Boolean = AUTO_CLAIM_LICENSE,
) {
    companion object {
        private const val AUTO_CLAIM_LICENSE = false
    }

    constructor(directDI: DirectDI) : this(
        licenseManager = directDI.instance(),
        refreshLicense = directDI.instance(),
        licenseClaimSource = directDI.instanceOrNull(),
    )

    fun launch(scope: CoroutineScope): Job = scope.launch {
        launch {
            refreshLicense()
                .attempt()
                .bind()
        }

        // Only auto-claim the license if the appropriate flag is enabled.
        if (!licenseAutoClaim) {
            return@launch
        }

        val source = licenseClaimSource
            ?: return@launch
        // Automatically claim a license for any
        // changes in the source.
        source
            .claims()
            .filter { result -> result !is RichResult.Loading }
            .mapNotNull { result ->
                val claim = result
                    .orNull()
                    ?.selectBestLicenseClaimCandidate()
                claim
            }
            .distinctUntilChanged()
            .onEach { claim ->
                licenseManager.claim(claim)
                    .attempt()
                    .bind()
            }
            .collect()
    }
}