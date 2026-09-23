package com.artemchep.keyguard.feature.home.vault.screen

import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

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

    @Test
    fun `normal folder list hides hidden accounts' folders`() = runTest {
        val folders = listOf(
            folder(id = "visible", accountId = "visible-account"),
            folder(id = "hidden", accountId = "hidden-account"),
        )
        val profiles = listOf(
            profile(accountId = "visible-account", hidden = false),
            profile(accountId = "hidden-account", hidden = true),
        )

        val result = filterHiddenProfiles(
            getFolders = getFolders(folders),
            getProfiles = getProfiles(flowOf(profiles)),
        ).first()

        assertEquals(listOf("visible"), result.map(DFolder::id))
    }

    @Test
    fun `preset by-id route keeps hidden account's folders`() = runTest {
        val hiddenFolder = folder(
            id = "folder",
            accountId = "hidden-account",
        )
        val neverEmittingProfiles = MutableSharedFlow<List<DProfile>>()

        val result = filterHiddenProfiles(
            getFolders = getFolders(listOf(hiddenFolder)),
            getProfiles = getProfiles(neverEmittingProfiles),
            filter = DFilter.ById(
                id = hiddenFolder.accountId,
                what = DFilter.ById.What.ACCOUNT,
            ),
        ).first()

        assertEquals(listOf(hiddenFolder), result)
        assertEquals(0, neverEmittingProfiles.subscriptionCount.value)
    }
}

private fun getFolders(
    folders: List<DFolder>,
): GetFolders = object : GetFolders {
    override fun invoke() = flowOf(folders)
}

private fun getProfiles(
    profilesFlow: Flow<List<DProfile>>,
): GetProfiles = object : GetProfiles {
    override fun invoke() = profilesFlow
}

private fun folder(
    id: String,
    accountId: String,
) = DFolder(
    id = id,
    accountId = accountId,
    revisionDate = Instant.fromEpochMilliseconds(0),
    service = BitwardenService(
        version = BitwardenService.VERSION,
    ),
    deleted = false,
    synced = true,
    name = id,
)

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
