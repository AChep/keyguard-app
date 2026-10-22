package com.artemchep.keyguard.feature.confirmation.organization

import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.apple.InMemoryScreenStateStore
import com.artemchep.keyguard.feature.home.vault.apple.RecordingClipboardService
import com.artemchep.keyguard.feature.home.vault.apple.RecordingNavigationController
import com.artemchep.keyguard.feature.home.vault.apple.RecordingShowMessage
import com.artemchep.keyguard.feature.home.vault.apple.newVaultListTestScope
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.test.createAccount
import com.artemchep.keyguard.test.createProfile
import com.artemchep.keyguard.ui.SimpleNote
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganizationConfirmationCopyTest {
    @Test
    fun `switching accounts updates warnings and confirmation together`() = runTest {
        for (blocksConfirmation in listOf(false, true)) {
            val scope = newVaultListTestScope(
                screenName = "copy-$blocksConfirmation",
                screenScope = backgroundScope,
                appScope = backgroundScope,
                screenStateStore = InMemoryScreenStateStore(),
                navigationController = RecordingNavigationController(backgroundScope),
                showMessage = RecordingShowMessage(),
                clipboardService = RecordingClipboardService(),
            )
            val warning = AccountChangeWarning(
                sourceAccountIds = setOf("source"),
                note = SimpleNote("Warning", SimpleNote.Type.WARNING),
                blocksConfirmation = blocksConfirmation,
            )
            val flow = scope.organizationConfirmationStateProducer(
                args = OrganizationConfirmationRoute.Args(
                    decor = OrganizationConfirmationRoute.Args.Decor("Copy"),
                    accountId = "source",
                    accountChangeWarnings = listOf(warning),
                ),
                transmitter = object : RouteResultTransmitter<OrganizationConfirmationResult> {
                    override fun invoke(unit: OrganizationConfirmationResult) = Unit
                },
                getAccounts = object : GetAccounts {
                    override fun invoke() = flowOf(listOf(
                        createAccount("source", AccountType.BITWARDEN),
                        createAccount("destination", AccountType.BITWARDEN),
                    ))
                },
                getProfiles = object : GetProfiles {
                    override fun invoke() = flowOf(listOf(createProfile("source"), createProfile("destination")))
                },
                getOrganizations = object : GetOrganizations {
                    override fun invoke() = flowOf(emptyList<DOrganization>())
                },
                getCollections = object : GetCollections { override fun invoke() = flowOf(emptyList<DCollection>()) },
                getFolders = object : GetFolders { override fun invoke() = flowOf(emptyList<DFolder>()) },
            )
            val sameAccount = flow.first { it.onConfirm != null }
            assertTrue(sameAccount.warnings.isEmpty())
            assertNotNull(sameAccount.content.getOrNull()).accounts.items.single { it.key == "destination" }.onClick!!()
            val differentAccount = flow.first {
                it.warnings.isNotEmpty() && it.content.getOrNull()?.accounts?.items?.any { item ->
                    item.key == "destination" && item.selected
                } == true
            }
            assertEquals(listOf(warning), differentAccount.warnings)
            if (blocksConfirmation) {
                assertNull(differentAccount.onConfirm)
            } else {
                assertNotNull(differentAccount.onConfirm)
            }

            assertNotNull(differentAccount.content.getOrNull()).accounts.items.single { it.key == "source" }.onClick!!()
            val back = flow.first { it.warnings.isEmpty() }
            assertNotNull(back.onConfirm)
        }
    }
}
