package com.artemchep.keyguard.feature.home.vault.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

internal val TEST_INSTANT: Instant = Instant.parse("2024-01-01T00:00:00Z")

internal fun createSecret(
    id: String,
    name: String = id,
    accountId: String = "account-id",
    folderId: String? = null,
    collectionIds: Set<String> = emptySet(),
    organizationId: String? = null,
    tags: List<String> = emptyList(),
    type: DSecret.Type = DSecret.Type.Login,
    favorite: Boolean = false,
    reprompt: Boolean = false,
    notes: String = "",
    uris: List<DSecret.Uri> = emptyList(),
    fields: List<DSecret.Field> = emptyList(),
    attachments: List<DSecret.Attachment> = emptyList(),
    login: DSecret.Login? = null,
    card: DSecret.Card? = null,
    identity: DSecret.Identity? = null,
): DSecret = DSecret(
    id = id,
    accountId = accountId,
    folderId = folderId,
    organizationId = organizationId,
    collectionIds = collectionIds,
    revisionDate = TEST_INSTANT,
    createdDate = TEST_INSTANT,
    archivedDate = null,
    deletedDate = null,
    service = BitwardenService(),
    name = name,
    notes = notes,
    favorite = favorite,
    reprompt = reprompt,
    synced = true,
    tags = tags,
    uris = uris,
    fields = fields,
    attachments = attachments,
    type = type,
    login = login,
    card = card,
    identity = identity,
)

internal fun createItem(
    source: DSecret,
    title: String = source.name,
    text: String? = source.login?.username ?: source.notes.takeIf { it.isNotBlank() },
): VaultItem2.Item = VaultItem2.Item(
    id = source.id,
    source = source,
    accentLight = Color(0xFF2196F3),
    accentDark = Color(0xFF64B5F6),
    accountId = source.accountId,
    groupId = null,
    revisionDate = TEST_INSTANT,
    createdDate = TEST_INSTANT,
    password = source.login?.password,
    passwordRevisionDate = source.login?.passwordRevisionDate,
    score = source.login?.passwordStrength,
    type = source.type.name,
    folderId = source.folderId,
    icon = VaultItemIcon.TextIcon("T"),
    feature = VaultItem2.Item.Feature.None,
    copyText = createCopyText(),
    token = source.login?.totp?.token,
    passwords = persistentListOf(),
    passkeys = persistentListOf(),
    attachments2 = persistentListOf(),
    title = AnnotatedString(title),
    text = text,
    favourite = source.favorite,
    attachments = source.attachments.isNotEmpty(),
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

private fun createCopyText(): CopyText = CopyText(
    clipboardService = object : com.artemchep.keyguard.common.service.clipboard.ClipboardService {
        override fun setPrimaryClip(value: String, concealed: Boolean) = Unit
        override fun clearPrimaryClip() = Unit
        override fun hasCopyNotification(): Boolean = true
    },
    translator = object : TranslatorScope {
        override suspend fun translate(res: StringResource): String = res.toString()
        override suspend fun translate(res: StringResource, vararg args: Any): String = res.toString()
        override suspend fun translate(
            res: PluralStringResource,
            quantity: Int,
            vararg args: Any,
        ): String = res.toString()
    },
    onMessage = { _: ToastMessage -> },
)
