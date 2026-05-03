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

class SendListStateProducerTest {
    @Test
    fun `premium bitwarden account enables file sends`() {
        val result = hasEligibleAccountForSendType(
            accounts = listOf(
                createAccount(
                    id = "account-1",
                    type = AccountType.BITWARDEN,
                ),
            ),
            profiles = listOf(
                createProfile(
                    accountId = "account-1",
                    premium = true,
                ),
            ),
            type = DSend.Type.File,
        )

        assertTrue(result)
    }

    @Test
    fun `file sends stay hidden without a premium bitwarden account`() {
        val result = hasEligibleAccountForSendType(
            accounts = listOf(
                createAccount(
                    id = "account-1",
                    type = AccountType.BITWARDEN,
                ),
                createAccount(
                    id = "account-2",
                    type = AccountType.KEEPASS,
                ),
            ),
            profiles = listOf(
                createProfile(
                    accountId = "account-1",
                    premium = false,
                ),
                createProfile(
                    accountId = "account-2",
                    premium = true,
                ),
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
