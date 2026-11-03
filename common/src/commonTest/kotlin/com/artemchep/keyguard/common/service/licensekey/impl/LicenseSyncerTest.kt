package com.artemchep.keyguard.common.service.licensekey.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.licensekey.LicenseManager
import com.artemchep.keyguard.common.service.licensekey.decoder.Kg2LicenseProductKind
import com.artemchep.keyguard.common.service.licensekey.model.LicenseClaimCandidate
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.service.licensekey.model.LicenseStatus
import com.artemchep.keyguard.common.usecase.PutCacheLicenseExpiry
import com.artemchep.keyguard.common.usecase.RefreshLicense
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LicenseSyncerTest {
    @Test
    fun cachesTheExpiryOfTheRedeemedLicense() = runTest {
        val redeemed = MutableStateFlow<LicenseEntitlement?>(null)
        val writes = mutableListOf<LicenseEntitlement.Expiry?>()
        LicenseSyncer(
            licenseManager = FakeLicenseManager(redeemed),
            refreshLicense = object : RefreshLicense { override fun invoke() = io(null) },
            putCacheLicenseExpiry = object : PutCacheLicenseExpiry {
                override fun invoke(expiry: LicenseEntitlement.Expiry?) = ioEffect { writes += expiry }
            },
            licenseClaimSource = null,
        ).launch(backgroundScope)
        runCurrent()

        redeemed.value = entitlement(LicenseEntitlement.Expiry.Lifetime)
        runCurrent()
        redeemed.value = entitlement(LicenseEntitlement.Expiry.PaidThrough("2027-03"))
        runCurrent()
        redeemed.value = entitlement(LicenseEntitlement.Expiry.PaidThrough("2027-03"), LicenseStatus.REVOKED)
        runCurrent()
        assertEquals(
            listOf(
                null,
                LicenseEntitlement.Expiry.Lifetime,
                LicenseEntitlement.Expiry.PaidThrough("2027-03"),
                null,
            ),
            writes,
        )
    }

    private fun entitlement(
        expiry: LicenseEntitlement.Expiry,
        status: LicenseStatus = LicenseStatus.ACTIVE,
    ) = LicenseEntitlement.Decoded(
        licenseKey = "key",
        licenseId = "id",
        tier = "premium",
        productKind = Kg2LicenseProductKind.Subscription,
        expiry = expiry,
        status = status,
        checkAfter = null,
        reason = null,
        source = null,
    )

    private class FakeLicenseManager(
        override val redeemed: Flow<LicenseEntitlement?>,
    ) : LicenseManager {
        override val claimed: Flow<LicenseEntitlement?> = flowOf(null)

        override fun redeem(licenseKey: String): IO<LicenseEntitlement> = error("Not used")

        override fun refreshRedeemedIfNeeded(force: Boolean): IO<LicenseEntitlement?> = error("Not used")

        override fun clearRedeemed(): IO<Unit> = error("Not used")

        override fun claim(
            claim: LicenseClaimCandidate,
            force: Boolean,
        ): IO<LicenseEntitlement?> = error("Not used")
    }
}
