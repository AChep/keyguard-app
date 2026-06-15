package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.common.service.backup.BackupRetention
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.SettingPaneComponents
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.theme.LocalExpressive
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.AutomaticBackupsEnabledContent(
    state: AutomaticBackupsSettingsState,
    dateFormatter: DateFormatter,
    configExpanded: MutableState<Boolean>,
    statusExpanded: MutableState<Boolean>,
    automationSectionTitle: String,
    managementSectionTitle: String,
    components: SettingPaneComponents,
) {
    val status = state.status
    val config = state.config
    automaticBackupsStatusSummary(
        key = "status",
        config = config,
        status = status,
        dateFormatter = dateFormatter,
        configExpanded = configExpanded,
        statusExpanded = statusExpanded,
    )

    item("config.header") {
        Section(
            text = managementSectionTitle,
            expressive = LocalExpressive.current,
        )
    }

    item("config.retention") {
        AutomaticBackupsRetentionRow(
            state = state,
            components = components,
        )
    }

    item("management.header") {
        Section(
            expressive = LocalExpressive.current,
        )
    }

    item("management.turn_off") {
        DisableAutomaticBackupsAction(
            onDisableClick = state.onDisableClick,
        )
    }

    item("details") {
        AutomaticBackupsDetailsSection(
            modifier = Modifier,
        )
    }
}

@Composable
private fun AutomaticBackupsRetentionRow(
    state: AutomaticBackupsSettingsState,
    components: SettingPaneComponents,
) {
    val maxSnapshots = state.config.retention.maxSnapshots
    components.KgPicker(
        icon = Icons.Outlined.History,
        title = stringResource(Res.string.pref_item_automatic_backups_retention_title),
        text = retentionText(maxSnapshots),
        dropdown = rememberAutomaticBackupsRetentionDropdown(
            maxSnapshots = maxSnapshots,
            onRetentionChange = state.onRetentionChange,
        ),
    )
}

@Composable
private fun rememberAutomaticBackupsRetentionDropdown(
    maxSnapshots: Int,
    onRetentionChange: (Int) -> Unit,
): List<FlatItemAction> {
    val fiveSnapshotsText = retentionText(5)
    val tenSnapshotsText = retentionText(10)
    val thirtySnapshotsText = retentionText(30)
    val sixtySnapshotsText = retentionText(60)
    val ninetySnapshotsText = retentionText(90)
    val neverClearText = retentionText(BackupRetention.NEVER_CLEAR_MAX_SNAPSHOTS)

    return remember(
        maxSnapshots,
        onRetentionChange,
        fiveSnapshotsText,
        tenSnapshotsText,
        thirtySnapshotsText,
        sixtySnapshotsText,
        ninetySnapshotsText,
        neverClearText,
    ) {
        persistentListOf(
            automaticBackupsRetentionAction(
                option = 5,
                title = fiveSnapshotsText,
                maxSnapshots = maxSnapshots,
                onRetentionChange = onRetentionChange,
            ),
            automaticBackupsRetentionAction(
                option = 10,
                title = tenSnapshotsText,
                maxSnapshots = maxSnapshots,
                onRetentionChange = onRetentionChange,
            ),
            automaticBackupsRetentionAction(
                option = 30,
                title = thirtySnapshotsText,
                maxSnapshots = maxSnapshots,
                onRetentionChange = onRetentionChange,
            ),
            automaticBackupsRetentionAction(
                option = 60,
                title = sixtySnapshotsText,
                maxSnapshots = maxSnapshots,
                onRetentionChange = onRetentionChange,
            ),
            automaticBackupsRetentionAction(
                option = 90,
                title = ninetySnapshotsText,
                maxSnapshots = maxSnapshots,
                onRetentionChange = onRetentionChange,
            ),
            automaticBackupsRetentionAction(
                option = BackupRetention.NEVER_CLEAR_MAX_SNAPSHOTS,
                title = neverClearText,
                maxSnapshots = maxSnapshots,
                onRetentionChange = onRetentionChange,
            ),
        )
    }
}

private fun automaticBackupsRetentionAction(
    option: Int,
    title: String,
    maxSnapshots: Int,
    onRetentionChange: (Int) -> Unit,
) = FlatItemAction(
    title = TextHolder.Value(title),
    selected = option == maxSnapshots,
    onClick = {
        onRetentionChange(option)
    },
)

@Composable
private fun DisableAutomaticBackupsAction(
    onDisableClick: () -> Unit,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = null,
        title = stringResource(Res.string.pref_item_automatic_backups_disable_title),
        contentColor = MaterialTheme.colorScheme.error,
        onClick = onDisableClick,
    )
}
