package com.artemchep.keyguard.feature.send

import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.test.createAccount
import com.artemchep.keyguard.test.createProfile
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
