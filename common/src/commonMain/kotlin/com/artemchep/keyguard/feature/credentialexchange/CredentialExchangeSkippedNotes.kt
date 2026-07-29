package com.artemchep.keyguard.feature.credentialexchange

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.DropdownIcon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The skipped-items introduction and count rows shared by the credential-
 * exchange export and import review screens.
 *
 * Emitted lazily, one row per item, because an expanded note lists every item
 * behind it and a vault where thousands of items each lost an attachment would
 * otherwise compose thousands of rows in a single frame — the export review
 * already had to stop doing exactly that once.
 */
internal fun LazyListScope.credentialExchangeSkippedNotes(
    notes: List<CredentialExchangeSkippedNote>,
) {
    if (notes.isEmpty()) {
        return
    }
    item(key = "skipped:spacer") {
        Spacer(
            modifier = Modifier
                .height(24.dp),
        )
    }
    item(key = "skipped:intro") {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Dimens.textHorizontalPadding,
                    vertical = 4.dp,
                ),
            text = stringResource(Res.string.skipped_items_text),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(alpha = MediumEmphasisAlpha),
        )
    }
    notes.forEach { note ->
        item(key = "skipped:${note.id}") {
            CredentialExchangeSkippedRow(
                note = note,
            )
        }
        if (!note.expanded) {
            return@forEach
        }
        note.titles.forEach { title ->
            item(key = "skipped:${note.id}:${title.text}") {
                CredentialExchangeSkippedTitleRow(
                    text = title.text,
                    count = title.count,
                )
            }
        }
        // What the titles could not account for. Several reasons fire on input
        // with no readable title at all, so without this the list would quietly
        // read as the whole story while the count above said otherwise.
        val remaining = note.remainingCount
        if (remaining > 0) {
            item(key = "skipped:${note.id}:more") {
                CredentialExchangeSkippedTitleRow(
                    text = pluralStringResource(
                        Res.plurals.skipped_and_more_note,
                        remaining,
                        remaining,
                    ),
                    count = 1,
                )
            }
        }
    }
}

@Composable
private fun CredentialExchangeSkippedRow(
    note: CredentialExchangeSkippedNote,
) {
    val onToggle = note.onToggle
    val updatedOnToggle by rememberUpdatedState(onToggle)
    val clickable = if (onToggle != null) {
        Modifier.clickable {
            updatedOnToggle?.invoke()
        }
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickable)
            .padding(
                horizontal = Dimens.textHorizontalPadding,
                vertical = 4.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Text(
            modifier = Modifier
                .weight(1f),
            text = pluralStringResource(note.res, note.count, note.count),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(alpha = MediumEmphasisAlpha),
        )
        // Absent, not disabled: a reason that attributed nothing has nothing to
        // open, and offering a chevron there trains the user to tap rows that
        // never say anything.
        if (onToggle != null) {
            DropdownIcon(
                modifier = Modifier
                    .size(16.dp),
                expanded = note.expanded,
            )
        }
    }
}

/**
 * One item behind an expanded note. Deliberately plain text rather than a
 * [CredentialExchangeItemRow]: that row's `shapeState` is computed against the
 * flat top-level list, so reusing it in a nested sub-list would group the
 * rounded corners wrong, and this block has always been plain text.
 */
@Composable
private fun CredentialExchangeSkippedTitleRow(
    text: String,
    count: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Dimens.textHorizontalPadding + 32.dp,
                end = Dimens.textHorizontalPadding,
                top = 2.dp,
                bottom = 2.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(alpha = MediumEmphasisAlpha),
        )
        // Only when one item lost several things for the same reason; the far
        // commoner one-each case reads as a plain list.
        if (count > 1) {
            Text(
                text = "($count)",
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current
                    .combineAlpha(alpha = DisabledEmphasisAlpha),
            )
        }
    }
}
