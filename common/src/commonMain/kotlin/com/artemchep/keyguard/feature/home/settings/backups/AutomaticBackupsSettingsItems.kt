package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

internal enum class AutomaticBackupsSettingsRow(
    val itemKey: String,
) {
    Unsupported("unsupported"),
    Location("location"),
    Password("password"),
    IncludeAttachments("include_attachments"),
    Retention("retention"),
    Disable("disable"),
}

internal val AutomaticBackupsUnsupportedRows = persistentListOf(
    AutomaticBackupsSettingsRow.Unsupported,
)

internal val AutomaticBackupsSetupRows = persistentListOf(
    AutomaticBackupsSettingsRow.Location,
    AutomaticBackupsSettingsRow.Password,
)

internal val AutomaticBackupsAttachmentRows = persistentListOf(
    AutomaticBackupsSettingsRow.IncludeAttachments,
)

internal val AutomaticBackupsAutomationRows = persistentListOf(
    AutomaticBackupsSettingsRow.Retention,
)

internal val AutomaticBackupsManagementRows = persistentListOf(
    AutomaticBackupsSettingsRow.Disable,
)

internal fun LazyListScope.automaticBackupsSettingsHeader(
    key: String,
    title: String,
) {
    item("$key.title") {
        Text(
            modifier = Modifier
                .animateItem()
                .padding(
                    start = Dimens.textHorizontalPadding,
                    end = Dimens.textHorizontalPadding,
                    top = 24.dp,
                    bottom = 8.dp,
                ),
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.combineAlpha(MediumEmphasisAlpha),
        )
    }
}

internal fun LazyListScope.automaticBackupsSettingsGroup(
    key: String,
    title: String?,
    rows: ImmutableList<AutomaticBackupsSettingsRow>,
    content: @Composable (AutomaticBackupsSettingsRow) -> Unit,
) {
    if (title != null) {
        automaticBackupsSettingsHeader(
            key = key,
            title = title,
        )
    }

    itemsIndexed(
        items = rows,
        key = { _, row -> "$key.${row.itemKey}" },
    ) { index, row ->
        val shapeState = getShapeState(rows, index) { _, _ -> true }
        val finalShapeState = if (rows.size == 1) {
            ShapeState.ALL
        } else {
            shapeState
        }
        CompositionLocalProvider(
            LocalSettingItemShape provides finalShapeState,
        ) {
            content(row)
        }
    }
}
