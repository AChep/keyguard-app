package com.artemchep.keyguard.feature.generator.util

import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeneratorEmailUtilTest {
    @Test
    fun `returns null for no profiles`() {
        assertNull(emptyList<DProfile>().findBestUserEmailOrNull())
    }

    @Test
    fun `prefers verified email over alphabetically greater unverified email`() {
        val profiles = listOf(
            createProfile(accountId = "1", email = "z@example.com", emailVerified = false),
            createProfile(accountId = "2", email = "a@example.com", emailVerified = true),
        )

        assertEquals("a@example.com", profiles.findBestUserEmailOrNull())
    }

    @Test
    fun `sums priorities of the same email across profiles`() {
        val profiles = listOf(
            createProfile(accountId = "1", email = "solo@example.com", emailVerified = true),
            createProfile(accountId = "2", email = "dup@example.com", emailVerified = true),
            createProfile(accountId = "3", email = "dup@example.com", emailVerified = true),
        )

        assertEquals("dup@example.com", profiles.findBestUserEmailOrNull())
    }

    @Test
    fun `picks the first email on a tie`() {
        val profiles = listOf(
            createProfile(accountId = "1", email = "z@example.com", emailVerified = null),
            createProfile(accountId = "2", email = "a@example.com", emailVerified = false),
        )

        assertEquals("z@example.com", profiles.findBestUserEmailOrNull())
    }
}

private fun createProfile(
    accountId: String,
    email: String,
    emailVerified: Boolean?,
) = DProfile(
    accountId = accountId,
    profileId = "profile-$accountId",
    keyBase64 = "key",
    privateKeyBase64 = "private-key",
    accountHost = "vault.example.com",
    email = email,
    emailVerified = emailVerified,
    accentColor = generateAccentColors(accountId),
    name = "User $accountId",
    description = "",
    premium = null,
    hidden = false,
    securityStamp = null,
    twoFactorEnabled = null,
    masterPasswordHint = null,
    masterPasswordHintEnabled = null,
    unofficialServer = false,
    serverVersion = null,
)
