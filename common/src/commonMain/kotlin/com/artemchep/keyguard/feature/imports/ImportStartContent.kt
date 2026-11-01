package com.artemchep.keyguard.feature.imports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.feature.home.settings.backups.icon
import com.artemchep.keyguard.feature.home.settings.backups.titleRes
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource

/**
 * The idle step of the import flow: where the items can come from.
 */
internal fun LazyListScope.importStartContent(
    stage: ImportState.Stage.Start,
) {
    item("start.header") {
        Text(
            modifier = Modifier
                .padding(
                    horizontal = Dimens.textHorizontalPadding,
                    vertical = 8.dp,
                ),
            text = stringResource(Res.string.import_header_text),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    stage.onCredentialExchange?.let { onCredentialExchange ->
        item("start.device.section") {
            Section(
                text = stringResource(Res.string.import_source_device_section),
                expressive = true,
            )
        }
        item("start.device.credential_exchange") {
            ImportCredentialExchangeItem(
                onClick = onCredentialExchange,
            )
        }
    }
    if (stage.formats.isNotEmpty()) {
        item("start.file.section") {
            Section(
                text = stringResource(Res.string.import_source_file_section),
                expressive = true,
            )
        }
        itemsIndexed(
            items = stage.formats,
            key = { _, format -> "start.file.${format.key}" },
        ) { index, format ->
            val shapeState = getShapeState(
                list = stage.formats,
                index = index,
                predicate = { _, _ -> true },
            )
            ImportFormatItem(
                format = format,
                shapeState = shapeState,
            )
        }
    }
    importBackupLocationsContent(
        locations = stage.backupLocations,
    )
    stage.onLearnMore?.let { onLearnMore ->
        item("start.learn_more") {
            Column {
                ImportLearnMoreButton(
                    onClick = onLearnMore,
                )
            }
        }
    }
}

private fun LazyListScope.importBackupLocationsContent(
    locations: List<ImportState.Stage.Start.BackupLocation>,
) {
    if (locations.isEmpty()) {
        return
    }
    item("start.backup.section") {
        Section(
            text = stringResource(Res.string.import_source_backup_section),
            expressive = true,
        )
    }
    itemsIndexed(
        items = locations,
        key = { _, location -> "start.backup.${location.kind.name}" },
    ) { index, location ->
        val shapeState = getShapeState(
            list = locations,
            index = index,
            predicate = { _, _ -> true },
        )
        ImportBackupLocationItem(
            location = location,
            shapeState = shapeState,
        )
    }
}

@Composable
private fun ImportCredentialExchangeItem(
    onClick: (LeContext) -> Unit,
) {
    val context by rememberUpdatedState(LocalLeContext)
    val updatedOnClick by rememberUpdatedState(onClick)
    FlatItemSimpleExpressive(
        leading = {
            Icon(Icons.Outlined.AppShortcut, null)
        },
        title = {
            Text(
                text = stringResource(Res.string.import_source_credential_exchange_title),
            )
        },
        text = {
            Text(
                text = stringResource(Res.string.credential_exchange_import_header_text),
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = {
            updatedOnClick(context)
        },
    )
}

@Composable
private fun ImportFormatItem(
    format: ImportState.Stage.Start.Format,
    shapeState: Int,
) {
    FlatItemSimpleExpressive(
        shapeState = shapeState,
        title = {
            Text(
                text = format.title,
            )
        },
        text = format.text?.let { text ->
            // composable
            {
                Text(
                    text = text,
                )
            }
        },
        trailing = {
            ChevronIcon()
        },
        onClick = format.onClick,
    )
}

@Composable
private fun ImportBackupLocationItem(
    location: ImportState.Stage.Start.BackupLocation,
    shapeState: Int,
) {
    FlatItemSimpleExpressive(
        shapeState = shapeState,
        leading = {
            Icon(location.kind.icon, null)
        },
        title = {
            Text(
                text = stringResource(location.kind.titleRes),
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = location.onClick,
    )
}

@Composable
private fun ColumnScope.ImportLearnMoreButton(
    onClick: () -> Unit,
) {
    val updatedOnClick by rememberUpdatedState(onClick)
    Spacer(
        modifier = Modifier
            .height(16.dp),
    )
    TextButton(
        modifier = Modifier
            .padding(
                horizontal = Dimens.buttonHorizontalPadding,
            ),
        onClick = updatedOnClick,
    ) {
        Icon(
            modifier = Modifier
                .size(18.dp),
            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
        )
        Spacer(
            modifier = Modifier
                .width(Dimens.buttonIconPadding),
        )
        Text(
            text = stringResource(Res.string.import_learn_more),
        )
    }
}
