package com.artemchep.keyguard.feature.home.vault.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.test.TEST_INSTANT
import com.artemchep.keyguard.test.testCopyText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow

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
    copyText = testCopyText(),
    token = source.login?.totp?.token,
    passwords = persistentListOf(),
    passkeys = persistentListOf(),
    attachments2 = persistentListOf(),
    title = AnnotatedString(title),
    text = text,
    favourite = source.favorite,
    attachments = source.attachments.isNotEmpty(),
    action = VaultItem2.Item.Action.None,
    localStateSource = VaultItem2.Item.LocalStateSource.PerItem(
        MutableStateFlow(
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
    ),
)
