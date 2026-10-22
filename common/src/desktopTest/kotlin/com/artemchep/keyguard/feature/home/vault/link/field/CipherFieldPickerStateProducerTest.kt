package com.artemchep.keyguard.feature.home.vault.link.field

import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.environments.EnvironmentFieldReference
import com.artemchep.keyguard.feature.home.vault.apple.InMemoryScreenStateStore
import com.artemchep.keyguard.feature.home.vault.apple.RecordingClipboardService
import com.artemchep.keyguard.feature.home.vault.apple.RecordingNavigationController
import com.artemchep.keyguard.feature.home.vault.apple.RecordingShowMessage
import com.artemchep.keyguard.feature.home.vault.apple.newVaultListTestScope
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.test.createSecret
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class CipherFieldPickerStateProducerTest {
    @Test
    fun `lists referenceable items, drills into their fields and returns the pick`() = runTest {
        val results = mutableListOf<CipherFieldPickerResult>()
        val transmitter = object : RouteResultTransmitter<CipherFieldPickerResult> {
            override fun invoke(unit: CipherFieldPickerResult) {
                results += unit
            }
        }
        val scope = newVaultListTestScope(
            screenName = "cipher_field_picker",
            screenScope = backgroundScope,
            appScope = backgroundScope,
            screenStateStore = InMemoryScreenStateStore(),
            navigationController = RecordingNavigationController(backgroundScope),
            showMessage = RecordingShowMessage(),
            clipboardService = RecordingClipboardService(),
        )
        val flow = scope.cipherFieldPickerStateProducer(
            args = CipherFieldPickerRoute.Args(
                accountId = ACCOUNT,
                excludedCipherId = "editor",
            ),
            transmitter = transmitter,
            getCiphers = object : GetCiphers {
                override fun invoke(): Flow<List<DSecret>> = flowOf(pickerCiphers())
            },
            getCollections = object : GetCollections {
                override fun invoke(): Flow<List<DCollection>> = flowOf(listOf(hiddenCollection()))
            },
            getAppIcons = object : GetAppIcons {
                override fun invoke(): Flow<Boolean> = flowOf(false)
            },
            getWebsiteIcons = object : GetWebsiteIcons {
                override fun invoke(): Flow<Boolean> = flowOf(false)
            },
        )

        val itemsState = flow.first { (it.step as? CipherFieldPickerState.Step.Items)?.items?.isNotEmpty() == true }
        val items = assertIs<CipherFieldPickerState.Step.Items>(itemsState.step).items
        assertEquals(listOf("custom", "password"), items.map { it.presentation.source.id })
        assertNull(itemsState.onBack)

        // Drill into the item with custom fields.
        items.first { it.presentation.source.id == "custom" }.onClick()
        val fieldsState = flow.first { it.step is CipherFieldPickerState.Step.Fields }
        val fields = assertIs<CipherFieldPickerState.Step.Fields>(fieldsState.step).fields
        assertEquals(listOf("API", "TOKEN"), fields.map { it.title })
        // A name used twice can not be picked.
        assertNull(fields.first { it.title == "API" }.onClick)

        // Back to the items, then pick the password of the other item.
        assertNotNull(fieldsState.onBack).invoke()
        val backState = flow.first { it.step is CipherFieldPickerState.Step.Items }
        assertIs<CipherFieldPickerState.Step.Items>(backState.step).items
            .first { it.presentation.source.id == "password" }
            .onClick()
        val passwordState = flow.first {
            (it.step as? CipherFieldPickerState.Step.Fields)?.fields?.any { field ->
                field.kind == CipherFieldPickerState.Field.Kind.Password
            } == true
        }
        assertIs<CipherFieldPickerState.Step.Fields>(passwordState.step).fields.single().onClick!!()
        assertEquals(
            listOf<CipherFieldPickerResult>(
                CipherFieldPickerResult.Confirm(ID_PASSWORD, EnvironmentFieldReference.Password, "password"),
            ),
            results,
        )
    }

    private fun pickerCiphers() = listOf(
        target("password", remoteId = ID_PASSWORD, login = DSecret.Login(password = "secret")),
        target(
            "custom",
            remoteId = ID_CUSTOM,
            fields = listOf(
                DSecret.Field("API", "a", type = DSecret.Field.Type.Hidden),
                DSecret.Field("API", "b", type = DSecret.Field.Type.Text),
                DSecret.Field("TOKEN", "c", type = DSecret.Field.Type.Text),
                DSecret.Field("LINKED", type = DSecret.Field.Type.Linked),
            ),
        ),
        // Not referenceable: another account, an environment, no fields,
        // only ambiguous fields, hidden passwords, never synced, the editor.
        target("other", remoteId = ID_OTHER, accountId = "other", login = DSecret.Login(password = "x")),
        target("env", remoteId = ID_ENV, type = DSecret.Type.Environment, login = DSecret.Login(password = "x")),
        target("empty", remoteId = ID_EMPTY),
        target(
            "ambiguous",
            remoteId = ID_AMBIGUOUS,
            fields = listOf(
                DSecret.Field("A", "1", type = DSecret.Field.Type.Text),
                DSecret.Field("A", "2", type = DSecret.Field.Type.Text),
            ),
        ),
        target(
            "hidden",
            remoteId = ID_HIDDEN,
            collectionIds = setOf("collection"),
            login = DSecret.Login(password = "x"),
        ),
        target("unsynced", remoteId = null, login = DSecret.Login(password = "x")),
        target("editor", remoteId = ID_EDITOR, login = DSecret.Login(password = "x")),
    )

    private fun target(
        id: String,
        remoteId: String?,
        accountId: String = ACCOUNT,
        type: DSecret.Type = DSecret.Type.Login,
        login: DSecret.Login? = null,
        fields: List<DSecret.Field> = emptyList(),
        collectionIds: Set<String> = emptySet(),
    ) = createSecret(
        id = id,
        accountId = accountId,
        type = type,
        login = login,
        fields = fields,
        collectionIds = collectionIds,
        service = BitwardenService(
            remote = remoteId?.let {
                BitwardenService.Remote(
                    id = it,
                    revisionDate = Instant.fromEpochSeconds(0),
                    deletedDate = null,
                )
            },
        ),
    )

    private fun hiddenCollection() = DCollection(
        id = "collection",
        externalId = null,
        organizationId = "organization",
        accountId = ACCOUNT,
        revisionDate = Instant.fromEpochSeconds(0),
        name = "Collection",
        readOnly = false,
        hidePasswords = true,
    )

    private companion object {
        const val ACCOUNT = "account"
        const val ID_PASSWORD = "00000000-0000-4000-8000-000000000001"
        const val ID_CUSTOM = "00000000-0000-4000-8000-000000000002"
        const val ID_OTHER = "00000000-0000-4000-8000-000000000003"
        const val ID_ENV = "00000000-0000-4000-8000-000000000004"
        const val ID_EMPTY = "00000000-0000-4000-8000-000000000005"
        const val ID_AMBIGUOUS = "00000000-0000-4000-8000-000000000006"
        const val ID_HIDDEN = "00000000-0000-4000-8000-000000000007"
        const val ID_EDITOR = "00000000-0000-4000-8000-000000000008"
    }
}
