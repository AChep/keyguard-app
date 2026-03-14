package com.artemchep.keyguard.feature.home.vault.quicksearch

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Password
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.*
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

class QuickSearchStateTest {
    @Test
    fun `quick search content keeps explicit results`() {
        val first = createVaultItem(id = "first")
        val second = createVaultItem(id = "second")

        val content = quickSearchContent(
            results = listOf(first, second),
            hasAccounts = true,
        )

        assertEquals(listOf(first, second), content.results)
        assertIs<QuickSearchEmptyState.Idle>(content.emptyState)
    }

    @Test
    fun `quick search content maps loading and no items states`() {
        val loading = quickSearchContent(
            results = null,
            hasAccounts = true,
        )
        val noItems = quickSearchContent(
            results = emptyList(),
            hasAccounts = true,
        )

        assertIs<QuickSearchEmptyState.Loading>(loading.emptyState)
        assertIs<QuickSearchEmptyState.NoItems>(noItems.emptyState)
    }

    @Test
    fun `quick search content maps add account state`() {
        val onAddAccount: (AccountType) -> Unit = {}

        val content = quickSearchContent(
            results = emptyList(),
            hasAccounts = false,
            onAddAccount = onAddAccount,
        )

        val emptyState = assertIs<QuickSearchEmptyState.AddAccount>(content.emptyState)
        assertSame(onAddAccount, emptyState.onAddAccount)
    }

    @Test
    fun `reconcile selected result defaults to first available item`() {
        val first = createVaultItem(id = "first")
        val second = createVaultItem(id = "second")
        val results = listOf(first, second)

        assertEquals("first", reconcileSelectedResultId(null, results))
        assertEquals("first", reconcileSelectedResultId("missing", results))
        assertEquals("second", reconcileSelectedResultId("second", results))
        assertNull(reconcileSelectedResultId("missing", emptyList()))
    }

    @Test
    fun `move selected result clamps within result bounds`() {
        val first = createVaultItem(id = "first")
        val second = createVaultItem(id = "second")
        val third = createVaultItem(id = "third")
        val results = listOf(first, second, third)

        assertEquals("first", moveSelectedResultId(null, results, 1))
        assertEquals("third", moveSelectedResultId(null, results, -1))
        assertEquals("second", moveSelectedResultId("first", results, 1))
        assertEquals("first", moveSelectedResultId("first", results, -1))
        assertEquals("third", moveSelectedResultId("third", results, 1))
        assertNull(moveSelectedResultId("first", emptyList(), 1))
    }

    @Test
    fun `move selected action index clamps and backfills from null`() {
        val actions = listOf(
            QuickSearchActionType.CopyPrimary,
            QuickSearchActionType.CopySecret,
            QuickSearchActionType.OpenInBrowser,
        )

        assertEquals(0, moveSelectedActionIndex(null, actions, 1))
        assertEquals(2, moveSelectedActionIndex(null, actions, -1))
        assertEquals(1, moveSelectedActionIndex(0, actions, 1))
        assertEquals(0, moveSelectedActionIndex(0, actions, -1))
        assertEquals(2, moveSelectedActionIndex(2, actions, 1))
        assertNull(moveSelectedActionIndex(null, emptyList(), 1))
    }

    @Test
    fun `default action resolves to first available quick action`() {
        val secret = createSecret(
            type = DSecret.Type.Login,
            login = DSecret.Login(
                password = "hunter2",
            ),
        )

        assertEquals(
            QuickSearchActionType.CopySecret,
            defaultQuickSearchActionType(secret),
        )
    }

    @Test
    fun `default action is null when no quick actions are available`() {
        val secret = createSecret(
            type = DSecret.Type.Login,
            login = DSecret.Login(),
        )

        assertNull(
            defaultQuickSearchActionType(secret),
        )
    }

    @Test
    fun `reconcile selected result falls back to first remaining item after removal`() {
        val first = createVaultItem(id = "first")
        val third = createVaultItem(id = "third")

        assertEquals(
            "first",
            reconcileSelectedResultId(
                currentSelectedItemId = "second",
                results = listOf(first, third),
            ),
        )
    }

    @Test
    fun `action types for full login expose copy otp and browser`() {
        val secret = createSecret(
            type = DSecret.Type.Login,
            login = DSecret.Login(
                username = "person@example.com",
                password = "hunter2",
                totp = createTotp(),
            ),
            uris = listOf(
                DSecret.Uri(uri = "https://example.com"),
            ),
        )

        assertEquals(
            listOf(
                QuickSearchActionType.CopyPrimary,
                QuickSearchActionType.CopySecret,
                QuickSearchActionType.CopyOtp,
                QuickSearchActionType.OpenInBrowser,
            ),
            quickSearchActionTypes(secret),
        )
    }

    @Test
    fun `action types for username only login expose only primary copy`() {
        val secret = createSecret(
            type = DSecret.Type.Login,
            login = DSecret.Login(
                username = "person@example.com",
            ),
        )

        assertEquals(
            listOf(
                QuickSearchActionType.CopyPrimary,
            ),
            quickSearchActionTypes(secret),
        )
    }

    @Test
    fun `action types for card include primary and secret copy`() {
        val secret = createSecret(
            type = DSecret.Type.Card,
            card = DSecret.Card(
                number = "4111111111111111",
                code = "123",
            ),
        )

        assertEquals(
            listOf(
                QuickSearchActionType.CopyPrimary,
                QuickSearchActionType.CopySecret,
            ),
            quickSearchActionTypes(secret),
        )
    }

    @Test
    fun `action types for secure note include value copy only`() {
        val secret = createSecret(
            type = DSecret.Type.SecureNote,
            notes = "bank instructions",
        )

        assertEquals(
            listOf(
                QuickSearchActionType.CopyPrimary,
            ),
            quickSearchActionTypes(secret),
        )
    }
}

internal fun createVaultItem(
    id: String,
    secret: DSecret = createSecret(id = id),
): VaultItem2.Item = VaultItem2.Item(
    id = id,
    source = secret,
    accentLight = Color(0xFF448AFF),
    accentDark = Color(0xFF82B1FF),
    accountId = secret.accountId,
    groupId = null,
    revisionDate = TEST_INSTANT,
    createdDate = TEST_INSTANT,
    password = secret.login?.password,
    passwordRevisionDate = null,
    score = null,
    type = secret.type.name,
    folderId = secret.folderId,
    icon = VaultItemIcon.VectorIcon(Icons.Outlined.Password),
    feature = VaultItem2.Item.Feature.None,
    copyText = createCopyText(),
    token = secret.login?.totp?.token,
    passwords = persistentListOf(),
    passkeys = persistentListOf(),
    attachments2 = persistentListOf(),
    title = AnnotatedString(secret.name),
    text = secret.login?.username ?: secret.notes.takeIf { it.isNotBlank() },
    favourite = false,
    attachments = false,
    action = VaultItem2.Item.Action.None,
    localStateFlow = MutableStateFlow(
        VaultItem2.Item.LocalState(
            openedState = VaultItem2.Item.OpenedState(isOpened = false),
            selectableItemState = SelectableItemState(
                selecting = false,
                selected = false,
                onClick = null,
                onLongClick = null,
            ),
        ),
    ),
)

internal fun createSecret(
    id: String = "cipher",
    type: DSecret.Type = DSecret.Type.Login,
    login: DSecret.Login? = if (type == DSecret.Type.Login) DSecret.Login() else null,
    card: DSecret.Card? = if (type == DSecret.Type.Card) DSecret.Card() else null,
    identity: DSecret.Identity? = if (type == DSecret.Type.Identity) DSecret.Identity() else null,
    sshKey: DSecret.SshKey? = if (type == DSecret.Type.SshKey) DSecret.SshKey() else null,
    notes: String = "",
    uris: List<DSecret.Uri> = emptyList(),
): DSecret = DSecret(
    id = id,
    accountId = "account",
    folderId = null,
    organizationId = null,
    collectionIds = emptySet(),
    revisionDate = TEST_INSTANT,
    createdDate = TEST_INSTANT,
    archivedDate = null,
    deletedDate = null,
    service = BitwardenService(),
    name = "Example $id",
    notes = notes,
    favorite = false,
    reprompt = false,
    synced = true,
    uris = uris,
    type = type,
    login = login,
    card = card,
    identity = identity,
    sshKey = sshKey,
)

internal fun createCopyText(
    clipboardService: ClipboardService = object : ClipboardService {
        override fun setPrimaryClip(value: String, concealed: Boolean) = Unit

        override fun clearPrimaryClip() = Unit

        override fun hasCopyNotification(): Boolean = true
    },
): CopyText = CopyText(
    clipboardService = clipboardService,
    translator = object : TranslatorScope {
        override suspend fun translate(res: StringResource): String = res.toString()

        override suspend fun translate(res: StringResource, vararg args: Any): String =
            res.toString()

        override suspend fun translate(
            res: PluralStringResource,
            quantity: Int,
            vararg args: Any,
        ): String = res.toString()
    },
    onMessage = { _: ToastMessage -> },
)

internal fun createTotp() = DSecret.Login.Totp(
    raw = "JBSWY3DPEHPK3PXP",
    token = TotpToken.TotpAuth(
        algorithm = com.artemchep.keyguard.common.model.CryptoHashAlgorithm.SHA_1,
        keyBase32 = "JBSWY3DPEHPK3PXP",
        raw = "JBSWY3DPEHPK3PXP",
        digits = 6,
        period = 30L,
    ),
)

internal val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
