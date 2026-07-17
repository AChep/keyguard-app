package com.artemchep.keyguard.feature.home.vault.screen

import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class VaultListPrivacyGateTest {
    @Test
    fun `normal list waits for profiles before publishing ciphers`() = runTest {
        val ciphersFlow = MutableSharedFlow<List<DSecret>>(replay = 1)
        val profilesFlow = MutableSharedFlow<List<DProfile>>(replay = 1)
        val result = async {
            filterHiddenProfiles(
                ciphersFlow = ciphersFlow,
                profilesFlow = profilesFlow,
            ).first()
        }

        ciphersFlow.emit(
            listOf(
                createSecret(id = "visible", accountId = "visible-account"),
                createSecret(id = "hidden", accountId = "hidden-account"),
            ),
        )
        runCurrent()
        assertFalse(result.isCompleted)

        profilesFlow.emit(
            listOf(
                profile(accountId = "visible-account", hidden = false),
                profile(accountId = "hidden-account", hidden = true),
            ),
        )

        assertEquals(
            listOf("visible"),
            result.await().map(DSecret::id),
        )
    }

    @Test
    fun `preset by-id route intentionally bypasses profile readiness`() = runTest {
        val hiddenCandidate = createSecret(
            id = "cipher",
            accountId = "hidden-account",
        )
        val neverEmittingProfiles = MutableSharedFlow<List<DProfile>>()

        val result = filterHiddenProfiles(
            ciphersFlow = flowOf(listOf(hiddenCandidate)),
            profilesFlow = neverEmittingProfiles,
            filter = DFilter.ById(
                id = hiddenCandidate.id,
                what = DFilter.ById.What.CIPHER,
            ),
        ).first()

        assertEquals(listOf(hiddenCandidate), result)
        assertEquals(0, neverEmittingProfiles.subscriptionCount.value)
    }

    @Test
    fun `profile visibility updates are applied to the shared cipher snapshot`() = runTest {
        val ciphers = listOf(
            createSecret(id = "cipher", accountId = "account"),
        )
        val profilesFlow = MutableSharedFlow<List<DProfile>>(replay = 1)
        val output = filterHiddenProfiles(
            ciphersFlow = flowOf(ciphers),
            profilesFlow = profilesFlow,
        )

        profilesFlow.emit(listOf(profile(accountId = "account", hidden = false)))
        assertEquals(ciphers, output.first())

        profilesFlow.emit(listOf(profile(accountId = "account", hidden = true)))
        assertEquals(emptyList(), output.first())
    }
}

private fun profile(
    accountId: String,
    hidden: Boolean,
) = DProfile(
    accountId = accountId,
    profileId = "profile-$accountId",
    keyBase64 = "key",
    privateKeyBase64 = "private-key",
    accountHost = "vault.example.com",
    email = "$accountId@example.com",
    emailVerified = true,
    accentColor = generateAccentColors(accountId),
    name = accountId,
    description = "",
    premium = null,
    hidden = hidden,
    securityStamp = null,
    twoFactorEnabled = null,
    masterPasswordHint = null,
    masterPasswordHintEnabled = null,
    unofficialServer = false,
    serverVersion = null,
)
