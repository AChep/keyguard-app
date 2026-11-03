package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.service.session.VaultLicenseSessionAccess
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.usecase.GetCacheLicenseExpiry
import com.artemchep.keyguard.common.usecase.GetLicensePremium
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GetVaultSessionLicensePremiumImplTest {
    @Test
    fun lockedVaultUsesTheCachedLicense() = runTest {
        val fixture = Fixture(cached = LicenseEntitlement.Expiry.Lifetime)
        assertTrue(fixture.premium().first())
    }

    @Test
    fun lockedVaultIgnoresAnExpiredCachedLicense() = runTest {
        val fixture = Fixture(cached = LicenseEntitlement.Expiry.PaidThrough("2000-01"))
        assertFalse(fixture.premium().first())
    }

    @Test
    fun lockedVaultWithoutCachedLicenseIsNotPremium() = runTest {
        val fixture = Fixture(cached = null)
        assertFalse(fixture.premium().first())
    }

    @Test
    fun unlockedVaultUsesTheLiveLicenseInsteadOfTheCache() = runTest {
        val fixture = Fixture(cached = LicenseEntitlement.Expiry.Lifetime)
        fixture.session.value = unlocked()
        assertFalse(fixture.premium().first())
    }

    @Test
    fun closedSessionUsesTheCachedLicense() = runTest {
        val fixture = Fixture(cached = LicenseEntitlement.Expiry.Lifetime, active = false)
        fixture.session.value = unlocked()
        assertTrue(fixture.premium().first())
    }

    @Test
    fun lockSwitchesFromTheLiveLicenseToTheCache() = runTest {
        val fixture = Fixture(cached = LicenseEntitlement.Expiry.Lifetime)
        fixture.session.value = unlocked()
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.premium().toList(values)
        }
        assertEquals(listOf(false), values)

        fixture.session.value = MasterSession.Empty()
        assertEquals(listOf(false, true), values)
    }

    private fun unlocked() = MasterSession.Key(
        masterKey = MasterKey(MasterKdfVersion.V0, ByteArray(32)),
        session = object : VaultSession {
            override val id = "vault"
            override val active = MutableStateFlow(true)
            override fun retire() = Unit
            override fun close() = Unit
        },
        origin = MasterSession.Key.Authenticated,
        createdAt = Instant.fromEpochSeconds(0),
    )

    private class Fixture(
        cached: LicenseEntitlement.Expiry?,
        active: Boolean = true,
    ) {
        val session = MutableStateFlow<MasterSession>(MasterSession.Empty())
        /** The license read from the unlocked vault. */
        val live = MutableStateFlow(false)
        val premium = GetVaultSessionLicensePremiumImpl(
            getVaultSession = object : GetVaultSession {
                override val valueOrNull get() = session.value
                override fun invoke() = session
            },
            sessionAccess = VaultLicenseSessionAccess {
                object : GetLicensePremium { override fun invoke() = live }
                    .takeIf { active }
            },
            getCacheLicenseExpiry = object : GetCacheLicenseExpiry { override fun invoke() = flowOf(cached) },
        )
    }
}
