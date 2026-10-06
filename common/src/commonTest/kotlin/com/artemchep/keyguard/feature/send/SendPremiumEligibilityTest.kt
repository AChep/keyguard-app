package com.artemchep.keyguard.feature.send

import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.test.createAccount
import com.artemchep.keyguard.test.createProfile
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
