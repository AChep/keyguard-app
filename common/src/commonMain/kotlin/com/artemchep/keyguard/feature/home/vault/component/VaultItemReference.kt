package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultItemPresentation
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.environments_reference_unavailable
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

private val ReferenceItemIconSize = 24.dp

/**
 * The item and the field a value is read from, or the field
 * and an error if the referenced item is not available.
 */
@Composable
fun VaultItemReference(
    modifier: Modifier = Modifier,
    item: VaultItemPresentation?,
    field: String,
    chevron: Boolean,
) {
    val label = referenceLabel(
        item = item,
        field = field,
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VaultItemReferenceIcon(
            item = item,
        )
        Spacer(
            modifier = Modifier
                .width(8.dp),
        )
        Text(
            modifier = Modifier
                .weight(1f, fill = false)
                .alignByBaseline(),
            text = label.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (label.text != null) {
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
            val contentColor = if (label.unavailable) {
                MaterialTheme.colorScheme.error
            } else {
                LocalContentColor.current
            }
            Text(
                modifier = Modifier
                    .alignByBaseline(),
                text = label.text,
                color = contentColor
                    .combineAlpha(MediumEmphasisAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (chevron) {
            Spacer(
                modifier = Modifier
                    .width(4.dp),
            )
            ChevronIcon()
        }
    }
}

/** The icon of the referenced item, or an error if it is not available. */
@Composable
private fun VaultItemReferenceIcon(
    item: VaultItemPresentation?,
) {
    if (item != null) {
        Box(
            modifier = Modifier
                .size(ReferenceItemIconSize),
        ) {
            AccountListItemTextIcon(
                item = item,
            )
        }
    } else {
        Icon(
            modifier = Modifier
                .size(ReferenceItemIconSize),
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * A [VaultViewBadge] with the item and the field a value is read from,
 * or the field and an error if the referenced item is not available.
 */
@Composable
fun VaultItemReferenceChip(
    modifier: Modifier = Modifier,
    reference: VaultViewItem.Value.Reference,
) {
    val label = referenceLabel(
        item = reference.item,
        field = reference.field,
    )
    VaultViewBadge(
        modifier = modifier,
        icon = if (label.unavailable) {
            Icons.Outlined.ErrorOutline
        } else {
            Icons.Outlined.Link
        },
        title = label.title,
        text = label.text,
        error = label.unavailable,
        onClick = reference.onClick,
    )
}

/** [vaultItemReferenceLabel] with the error text of an unavailable item. */
@Composable
private fun referenceLabel(
    item: VaultItemPresentation?,
    field: String,
): VaultItemReferenceLabel {
    val label = vaultItemReferenceLabel(
        item = item,
        field = field,
    )
    return if (label.unavailable) {
        label.copy(text = stringResource(Res.string.environments_reference_unavailable))
    } else {
        label
    }
}

data class VaultItemReferenceLabel(
    val title: String,
    /** `null` if the item has no name, or is not available. */
    val text: String?,
    /**
     * `true` if the referenced item is not available, the
     * UI shows the error text in place of the [text].
     */
    val unavailable: Boolean,
)

/**
 * The item name and the field, or the field alone if the item is not
 * available. An item without a name is shown by its field alone.
 */
fun vaultItemReferenceLabel(
    item: VaultItemPresentation?,
    field: String,
): VaultItemReferenceLabel {
    if (item == null) {
        return VaultItemReferenceLabel(
            title = field,
            text = null,
            unavailable = true,
        )
    }
    val name = item.title.text
    return if (name.isNotBlank()) {
        VaultItemReferenceLabel(
            title = name,
            text = field,
            unavailable = false,
        )
    } else {
        VaultItemReferenceLabel(
            title = field,
            text = null,
            unavailable = false,
        )
    }
}
