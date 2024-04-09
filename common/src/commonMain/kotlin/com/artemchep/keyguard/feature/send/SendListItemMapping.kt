package com.artemchep.keyguard.feature.send

import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.iconImageVector
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.short
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.StateFlow

suspend fun DSend.toVaultListItem(
    copy: CopyText,
    appIcons: Boolean,
    websiteIcons: Boolean,
    groupId: String? = null,
    dateFormatter: DateFormatter,
    onClick: (List<FlatItemAction>) -> SendItem.Item.Action,
    localStateFlow: StateFlow<SendItem.Item.LocalState>,
): SendItem.Item {
    val d = when {
        text != null -> text.createText()
        file != null -> file.createFile()
        else -> {
            TypeSpecific(
                text = notes,
            )
        }
    }

    val icon = toVaultItemIcon(
        appIcons = appIcons,
        websiteIcons = websiteIcons,
    )
    val deletion = deletedDate
        ?.let(dateFormatter::formatDate)
    return SendItem.Item(
        id = id,
        source = this,
        accentLight = accentLight,
        accentDark = accentDark,
        accountId = accountId,
        groupId = groupId,
        revisionDate = revisionDate,
        createdDate = createdDate,
        hasPassword = hasPassword,
        hasFile = type == DSend.Type.File,
        icon = icon,
        type = type.name,
        title = AnnotatedString(name.trim()),
        text = d.text.trim(),
        notes = notes.trim(),
        deletion = deletion,
        action = onClick(d.actions),
        localStateFlow = localStateFlow,
    )
}

fun DSend.toVaultItemIcon(
    appIcons: Boolean,
    websiteIcons: Boolean,
): VaultItemIcon = kotlin.run {
    val vectorIconSrc = type.iconImageVector()
    val textIcon = if (name.isNotBlank()) {
        VaultItemIcon.TextIcon.short(name)
    } else {
        null
    }
    val vectorIcon = VaultItemIcon.VectorIcon(
        imageVector = vectorIconSrc,
    )
    textIcon ?: vectorIcon
}

private data class TypeSpecific(
    val text: String,
    val actions: List<FlatItemAction> = emptyList(),
)

private fun DSend.Text.createText(
): TypeSpecific {
    return TypeSpecific(
        text = text.orEmpty(),
    )
}

private fun DSend.File.createFile(
): TypeSpecific {
    val size = size?.let(::humanReadableByteCountSI)
        ?.let { " ($it)" }
    return TypeSpecific(
        text = fileName + size,
    )
}
