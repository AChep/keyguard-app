package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.SettingPaneComponents
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.LocalExpressive
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.AutomaticBackupsSetupContent(
    state: AutomaticBackupsSettingsState,
    isSupported: Boolean,
    setupSectionTitle: String,
    components: SettingPaneComponents,
) {
    item("wizard.copy") {
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = stringResource(Res.string.pref_item_automatic_backups_setup_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = stringResource(Res.string.pref_item_automatic_backups_setup_logic),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
    }

    if (!isSupported) {
        automaticBackupsSettingsGroup(
            key = "unsupported",
            title = setupSectionTitle,
            rows = AutomaticBackupsUnsupportedRows,
        ) { row ->
            when (row) {
                AutomaticBackupsSettingsRow.Unsupported -> AutomaticBackupsUnsupportedRow(components)
                else -> Unit
            }
        }
        return
    }

    state.setupError?.let { error ->
        item("wizard.error") {
            FlatSimpleNote(
                type = SimpleNote.Type.WARNING,
                text = error,
            )
        }
    }

    item("config.header") {
        Section(
            text = setupSectionTitle,
            expressive = LocalExpressive.current,
        )
    }

    item("config.basic.location") {
        CompositionLocalProvider(
            LocalSettingItemShape provides ShapeState.START,
        ) {
            AutomaticBackupsLocationRow(
                state = state,
                components = components,
            )
        }
    }

    item("config.basic.password") {
        CompositionLocalProvider(
            LocalSettingItemShape provides ShapeState.END,
        ) {
            AutomaticBackupsPasswordRow(
                state = state,
                components = components,
            )
        }
    }

    item("config.attachments.spacer") {
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
    }

    item("config.attachments.switch") {
        AutomaticBackupsIncludeAttachmentsRow(
            state = state,
            components = components,
        )
    }

    item("details") {
        AutomaticBackupsDetailsSection(
            modifier = Modifier,
        )
    }
}

@Composable
private fun AutomaticBackupsUnsupportedRow(
    components: SettingPaneComponents,
) {
    components.KgAction(
        icon = Icons.Outlined.Info,
        title = stringResource(Res.string.pref_item_automatic_backups_unsupported_title),
        text = stringResource(Res.string.pref_item_automatic_backups_unsupported_text),
        enabled = false,
    )
}

@Composable
private fun AutomaticBackupsLocationRow(
    state: AutomaticBackupsSettingsState,
    components: SettingPaneComponents,
) {
    val store = state.setup.store.value
    val isLocal = store is BackupStoreConfig.Local
    val isWebDav = store is BackupStoreConfig.WebDav

    val onLocalClick = state.onLocationClick
        .takeUnless { state.isTestingLocation }
    val onWebDavClick = state.onWebDavLocationClick
        .takeUnless { state.isTestingLocation }
    val dropdown = remember(
        isLocal,
        isWebDav,
        onLocalClick,
        onWebDavClick,
    ) {
        persistentListOf(
            FlatItemAction(
                id = "settings.automaticBackups.store.local",
                icon = Icons.Outlined.Folder,
                title = TextHolder.Res(Res.string.pref_item_automatic_backups_store_folder_title),
                selected = isLocal,
                onClick = onLocalClick,
            ),
            FlatItemAction(
                id = "settings.automaticBackups.store.webdav",
                icon = Icons.Outlined.Cloud,
                title = TextHolder.Res(Res.string.pref_item_automatic_backups_store_webdav_title),
                selected = isWebDav,
                onClick = onWebDavClick,
            ),
        )
    }

    val locationText = backupLocationText(store)
    val icon = when (store) {
        is BackupStoreConfig.Local -> Icons.Outlined.Folder
        is BackupStoreConfig.WebDav -> Icons.Outlined.Cloud
    }
    components.KgPicker(
        icon = icon,
        title = stringResource(Res.string.pref_item_automatic_backups_location_title),
        text = locationText,
        dropdown = dropdown,
    )
}

@Composable
private fun AutomaticBackupsPasswordRow(
    state: AutomaticBackupsSettingsState,
    components: SettingPaneComponents,
) {
    val hasPassword = state.setup.password.value.isNotEmpty()
    val text = if (hasPassword) {
        stringResource(Res.string.pref_item_automatic_backups_password_set)
    } else {
        stringResource(Res.string.pref_item_automatic_backups_password_not_set)
    }
    components.KgAction(
        icon = Icons.Outlined.Password,
        title = stringResource(Res.string.pref_item_automatic_backups_password_title),
        text = text,
        onClick = state.onPasswordClick,
    )
}

@Composable
private fun AutomaticBackupsIncludeAttachmentsRow(
    state: AutomaticBackupsSettingsState,
    components: SettingPaneComponents,
) {
    val includeAttachmentsState = state.setup.includeAttachments
    val onCheckedChange = remember(includeAttachmentsState) {
        { checked: Boolean ->
            includeAttachmentsState.value = checked
        }
    }
    components.KgSwitch(
        title = stringResource(Res.string.pref_item_automatic_backups_include_attachments_title),
        checked = includeAttachmentsState.value,
        onCheckedChange = onCheckedChange,
    )
}
