package com.artemchep.keyguard.feature.send

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendPremiumEligibilityTest {
    @Test
    fun `file send allowed for premium bitwarden account`() {
        val result = canUseAccountForSendType(
            account = createAccount(
                id = "account-1",
                type = AccountType.BITWARDEN,
            ),
            profile = createProfile(
                accountId = "account-1",
                premium = true,
            ),
            type = DSend.Type.File,
        )

        assertTrue(result)
    }

    @Test
    fun `file send denied for non premium bitwarden account`() {
        val result = canUseAccountForSendType(
            account = createAccount(
                id = "account-1",
                type = AccountType.BITWARDEN,
            ),
            profile = createProfile(
                accountId = "account-1",
                premium = false,
            ),
            type = DSend.Type.File,
        )

        assertFalse(result)
    }

    @Test
    fun `text send allowed for non premium bitwarden account`() {
        val result = canUseAccountForSendType(
            account = createAccount(
                id = "account-1",
                type = AccountType.BITWARDEN,
            ),
            profile = createProfile(
                accountId = "account-1",
                premium = false,
            ),
            type = DSend.Type.Text,
        )

        assertTrue(result)
    }

    @Test
    fun `send denied for non bitwarden premium account`() {
        val result = canUseAccountForSendType(
            account = createAccount(
                id = "account-1",
                type = AccountType.KEEPASS,
            ),
            profile = createProfile(
                accountId = "account-1",
                premium = true,
            ),
            type = DSend.Type.Text,
        )

        assertFalse(result)
    }

    @Test
    fun `null premium is treated as not premium for file sends`() {
        val result = canUseAccountForSendType(
            account = createAccount(
                id = "account-1",
                type = AccountType.BITWARDEN,
            ),
            profile = createProfile(
                accountId = "account-1",
                premium = null,
            ),
            type = DSend.Type.File,
        )

        assertFalse(result)
    }
}

private fun createAccount(
    id: String,
    type: AccountType,
) = DAccount(
    id = AccountId(id),
    username = "user@example.com",
    host = "vault.example.com",
    webVaultUrl = "https://vault.example.com",
    localVaultUrl = null,
    type = type,
    faviconServer = null,
)

private fun createProfile(
    accountId: String,
    premium: Boolean?,
) = DProfile(
    accountId = accountId,
    profileId = "profile-$accountId",
    keyBase64 = "key",
    privateKeyBase64 = "private-key",
    accountHost = "vault.example.com",
    email = "$accountId@example.com",
    emailVerified = true,
    accentColor = generateAccentColors(accountId),
    name = "User $accountId",
    description = "",
    premium = premium,
    hidden = false,
    securityStamp = null,
    twoFactorEnabled = null,
    masterPasswordHint = null,
    masterPasswordHintEnabled = null,
    unofficialServer = false,
    serverVersion = null,
)
