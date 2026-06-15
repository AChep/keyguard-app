package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import org.jetbrains.compose.resources.stringResource

private const val AutomaticBackupsRepositoryTreeSample = "Keyguard Backups/\n" +
        "├─ repo.zip\n" +
        "├─ indexes/\n" +
        "│  ├─ 000000000001-a1b2.zip\n" +
        "│  └─ 000000000002-c3d4.zip\n" +
        "├─ snapshots/\n" +
        "│  ├─ 2026-05-28T10-42-11Z-a1b2.zip\n" +
        "│  └─ 2026-05-28T12-18-04Z-c3d4.zip\n" +
        "└─ blobs/\n" +
        "   └─ 4f/\n" +
        "      └─ 9a/\n" +
        "         └─ 4f9a...e7.zip"

@Composable
internal fun AutomaticBackupsDetailsSection(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        Spacer(
            modifier = Modifier
                .height(32.dp),
        )
        Icon(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = LocalContentColor.current
                .combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Column(
            modifier = Modifier
                .padding(
                    start = Dimens.textHorizontalPadding,
                    end = Dimens.textHorizontalPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            AutomaticBackupsDetailText(
                title = stringResource(Res.string.pref_item_automatic_backups_details_321_title),
                text = stringResource(Res.string.pref_item_automatic_backups_details_321_text),
            ) {
                AutomaticBackupsRuleLine(
                    value = "3",
                    text = stringResource(Res.string.pref_item_automatic_backups_details_321_three),
                )
                AutomaticBackupsRuleLine(
                    value = "2",
                    text = stringResource(Res.string.pref_item_automatic_backups_details_321_two),
                )
                AutomaticBackupsRuleLine(
                    value = "1",
                    text = stringResource(Res.string.pref_item_automatic_backups_details_321_one),
                )
            }
            AutomaticBackupsRepositoryTree()
            AutomaticBackupsDetailText(
                title = stringResource(Res.string.pref_item_automatic_backups_details_retention_title),
                text = stringResource(Res.string.pref_item_automatic_backups_details_retention_text),
            )
            AutomaticBackupsDetailText(
                title = stringResource(Res.string.pref_item_automatic_backups_details_sync_title),
                text = stringResource(Res.string.pref_item_automatic_backups_details_sync_text),
            )
        }
    }
}

@Composable
private fun AutomaticBackupsDetailText(
    title: String,
    text: String,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
                .combineAlpha(MediumEmphasisAlpha),
        )
        content?.invoke(this)
    }
}

@Composable
private fun AutomaticBackupsRuleLine(
    value: String,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(
                    horizontal = 9.dp,
                    vertical = 3.dp,
                ),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            modifier = Modifier
                .weight(1f),
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
                .combineAlpha(MediumEmphasisAlpha),
        )
    }
}

@Composable
private fun AutomaticBackupsRepositoryTree() {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.pref_item_automatic_backups_details_repository_structure_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(Res.string.pref_item_automatic_backups_details_repository_structure_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
                .combineAlpha(MediumEmphasisAlpha),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(12.dp),
        ) {
            SelectionContainer {
                Text(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState()),
                    text = AutomaticBackupsRepositoryTreeSample,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = monoFontFamily,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurface
                        .combineAlpha(MediumEmphasisAlpha),
                )
            }
        }
    }
}
