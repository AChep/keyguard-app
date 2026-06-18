package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupRunProgress
import com.artemchep.keyguard.common.service.backup.BackupRunProgressDetails
import com.artemchep.keyguard.common.service.backup.BackupStatus
import com.artemchep.keyguard.common.service.backup.BackupStep
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.home.vault.component.FlatSurfaceExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.DropdownIcon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.util.HorizontalDivider
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.automaticBackupsStatusSummary(
    key: String,
    config: BackupConfig,
    status: BackupStatus,
    dateFormatter: DateFormatter,
    configExpanded: MutableState<Boolean>,
    statusExpanded: MutableState<Boolean>,
) {
    item("$key.summary") {
        AutomaticBackupsStatusPanel(
            modifier = Modifier
                .fillMaxWidth(),
            config = config,
            status = status,
            dateFormatter = dateFormatter,
            configExpanded = configExpanded,
            statusExpanded = statusExpanded,
        )
    }
}

@Composable
private fun AutomaticBackupsStatusPanel(
    modifier: Modifier = Modifier,
    config: BackupConfig,
    status: BackupStatus,
    dateFormatter: DateFormatter,
    configExpanded: MutableState<Boolean>,
    statusExpanded: MutableState<Boolean>,
) {
    val iconTint = when {
        status.currentRun != null -> MaterialTheme.colorScheme.primary
        status.lastErrorMessage != null -> MaterialTheme.colorScheme.error
        status.lastSkippedReason != null -> MaterialTheme.colorScheme.tertiary
        status.lastSnapshotId != null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier,
    ) {
        FlatSurfaceExpressive(
            shapeState = ShapeState.START,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Dimens.contentPadding,
                        vertical = 16.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = iconTint.combineAlpha(0.12f),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(10.dp),
                    ) {
                        Icon(
                            imageVector = backupStatusIcon(status),
                            contentDescription = null,
                            tint = iconTint,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = backupStatusTitle(status),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = backupStatusText(status, dateFormatter),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                                .combineAlpha(MediumEmphasisAlpha),
                        )
                    }
                }

                status.currentRun?.let { progress ->
                    BackupProgressFooter(progress)
                }
            }
        }

        FlatItemLayoutExpressive(
            shapeState = ShapeState.CENTER,
            content = {
                FlatItemTextContent(
                    title = {
                        Text(
                            text = stringResource(Res.string.pref_item_automatic_backups_panel_repository_title),
                        )
                    },
                )
                ExpandedIfNotEmpty(
                    Unit
                        .takeIf { configExpanded.value },
                ) {
                    Column(
                        modifier = Modifier
                            .padding(top = 4.dp),
                    ) {
                        AutomaticBackupsStatusPanelLine(
                            label = stringResource(Res.string.pref_item_automatic_backups_location_title),
                            value = backupLocationText(config.store).orEmpty(),
                        )
                        AutomaticBackupsStatusPanelLine(
                            label = stringResource(Res.string.pref_item_automatic_backups_password_title),
                            value = if (config.password?.value.isNullOrEmpty()) {
                                stringResource(Res.string.no_password)
                            } else {
                                stringResource(Res.string.pref_item_automatic_backups_password_set_summary)
                            },
                        )
                        AutomaticBackupsStatusPanelLine(
                            label = stringResource(Res.string.pref_item_automatic_backups_include_attachments_title),
                            value = if (config.includeAttachments) {
                                stringResource(Res.string.pref_item_automatic_backups_include_attachments_enabled_summary)
                            } else {
                                stringResource(Res.string.pref_item_automatic_backups_include_attachments_disabled_summary)
                            },
                        )
                    }
                }
            },
            trailing = {
                DropdownIcon(
                    expanded = configExpanded.value,
                )
            },
            onClick = {
                configExpanded.value = !configExpanded.value
            },
        )

        FlatItemLayoutExpressive(
            shapeState = ShapeState.END,
            content = {
                FlatItemTextContent(
                    title = {
                        Text(
                            text = stringResource(Res.string.pref_item_automatic_backups_panel_last_sync_title),
                        )
                    },
                )
                ExpandedIfNotEmpty(
                    Unit
                        .takeIf { statusExpanded.value },
                ) {
                    Column(
                        modifier = Modifier
                            .padding(top = 4.dp),
                    ) {
                        AutomaticBackupsLastSyncLines(
                            status = status,
                            dateFormatter = dateFormatter,
                        )
                    }
                }
            },
            trailing = {
                DropdownIcon(
                    expanded = statusExpanded.value,
                )
            },
            onClick = {
                statusExpanded.value = !statusExpanded.value
            },
        )
    }
}

@Composable
private fun AutomaticBackupsLastSyncLines(
    status: BackupStatus,
    dateFormatter: DateFormatter,
) {
    AutomaticBackupsStatusPanelLine(
        label = stringResource(Res.string.pref_item_automatic_backups_panel_status_label),
        value = backupStatusTitle(status),
    )
    val currentRun = status.currentRun
    if (currentRun != null) {
        AutomaticBackupsStatusPanelLine(
            label = stringResource(Res.string.pref_item_automatic_backups_panel_started_label),
            value = dateFormatter.formatDateTime(currentRun.startedAt),
        )
        AutomaticBackupsStatusPanelLine(
            label = stringResource(Res.string.pref_item_automatic_backups_panel_progress_label),
            value = backupRunProgressText(currentRun),
        )
    } else {
        status.lastStartedAt?.let { startedAt ->
            AutomaticBackupsStatusPanelLine(
                label = stringResource(Res.string.pref_item_automatic_backups_panel_started_label),
                value = dateFormatter.formatDateTime(startedAt),
            )
        }
        status.lastFinishedAt?.let { finishedAt ->
            AutomaticBackupsStatusPanelLine(
                label = stringResource(Res.string.pref_item_automatic_backups_panel_finished_label),
                value = dateFormatter.formatDateTime(finishedAt),
            )
        }
        status.lastSnapshotId?.let { snapshotId ->
            AutomaticBackupsStatusPanelLine(
                label = stringResource(Res.string.pref_item_automatic_backups_panel_snapshot_label),
                value = snapshotId,
            )
        }
        status.lastErrorMessage?.let { error ->
            AutomaticBackupsStatusPanelLine(
                label = stringResource(Res.string.pref_item_automatic_backups_panel_error_label),
                value = error,
            )
        }
        status.lastSkippedReason?.let { reason ->
            AutomaticBackupsStatusPanelLine(
                label = stringResource(Res.string.pref_item_automatic_backups_panel_skipped_label),
                value = backupSkippedReasonText(reason),
            )
        }
        status.lastStats?.let { stats ->
            AutomaticBackupsStatusPanelLine(
                label = stringResource(Res.string.pref_item_automatic_backups_panel_vault_items_label),
                value = stats.cipherCount.toString(),
            )
            AutomaticBackupsStatusPanelLine(
                label = stringResource(Res.string.pref_item_automatic_backups_panel_attachments_label),
                value = stats.attachmentCount.toString(),
            )
            AutomaticBackupsStatusPanelLine(
                label = stringResource(Res.string.pref_item_automatic_backups_panel_blobs_label),
                value = stringResource(
                    Res.string.pref_item_automatic_backups_panel_blobs_value,
                    stats.newBlobCount,
                    stats.reusedBlobCount,
                ),
            )
        }
        status.lastChangedAt?.let { changedAt ->
            AutomaticBackupsStatusPanelLine(
                label = stringResource(Res.string.pref_item_automatic_backups_panel_last_change_label),
                value = dateFormatter.formatDateTime(changedAt),
            )
        }
    }
    AutomaticBackupsStatusPanelLine(
        label = stringResource(Res.string.pref_item_automatic_backups_panel_pending_changes_label),
        value = stringResource(
            if (status.isDirty) {
                Res.string.yes
            } else {
                Res.string.no
            },
        ),
    )
}

@Composable
private fun AutomaticBackupsStatusPanelLine(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            modifier = Modifier
                .weight(0.48f),
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.combineAlpha(MediumEmphasisAlpha),
        )
        Text(
            modifier = Modifier
                .weight(1f),
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.combineAlpha(MediumEmphasisAlpha),
        )
    }
}

@Composable
private fun backupStatusTitle(
    status: BackupStatus,
): String = when {
    status.currentRun != null -> stringResource(Res.string.pref_item_automatic_backups_status_running_title)
    status.lastErrorMessage != null -> stringResource(Res.string.pref_item_automatic_backups_status_error_title)
    status.lastSkippedReason != null -> stringResource(Res.string.pref_item_automatic_backups_status_skipped_title)
    status.lastSnapshotId != null -> stringResource(Res.string.pref_item_automatic_backups_status_success_title)
    else -> stringResource(Res.string.pref_item_automatic_backups_status_never_title)
}

@Composable
private fun backupStatusText(
    status: BackupStatus,
    dateFormatter: DateFormatter,
): String = when {
    status.currentRun != null -> backupRunProgressText(status.currentRun)

    status.lastErrorMessage != null -> stringResource(
        Res.string.pref_item_automatic_backups_status_error_text,
        status.lastErrorMessage.orEmpty(),
    )

    status.lastSkippedReason != null -> stringResource(
        Res.string.pref_item_automatic_backups_status_skipped_text,
        backupSkippedReasonText(status.lastSkippedReason),
    )

    status.lastFinishedAt != null && status.lastStats != null -> {
        val stats = status.lastStats
        stringResource(
            Res.string.pref_item_automatic_backups_status_success_text,
            dateFormatter.formatDateTime(status.lastFinishedAt),
            stats.cipherCount,
            stats.attachmentCount,
            stats.newBlobCount,
            stats.reusedBlobCount,
        )
    }

    status.lastFinishedAt != null -> stringResource(
        Res.string.pref_item_automatic_backups_status_finished_text,
        dateFormatter.formatDateTime(status.lastFinishedAt),
    )

    else -> stringResource(Res.string.pref_item_automatic_backups_status_never_text)
}

@Composable
private fun backupSkippedReasonText(
    reason: String?,
): String = when (reason) {
    "vault_locked" -> stringResource(Res.string.pref_item_automatic_backups_status_reason_vault_locked)
    "backup_not_configured" -> stringResource(Res.string.pref_item_automatic_backups_status_reason_not_configured)
    else -> reason ?: stringResource(Res.string.pref_item_automatic_backups_status_reason_unknown)
}

private fun backupStatusIcon(
    status: BackupStatus,
): ImageVector = when {
    status.currentRun != null -> Icons.Outlined.Sync
    status.lastErrorMessage != null -> Icons.Outlined.Info
    status.lastSkippedReason != null -> Icons.Outlined.Info
    status.lastSnapshotId != null -> Icons.Outlined.Backup
    else -> Icons.Outlined.History
}

@Composable
private fun BackupProgressFooter(
    progress: BackupRunProgress,
) {
    val p = progress.details.progressPercentage()
    if (p != null) {
        LinearProgressIndicator(
            progress = { p },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun backupRunProgressText(
    progress: BackupRunProgress,
): String {
    val step = backupStepText(progress.step)
    val details = backupProgressDetailsText(progress.details)
    return if (details != null) {
        stringResource(
            Res.string.pref_item_automatic_backups_status_running_text_with_progress,
            step,
            details,
        )
    } else {
        step
    }
}

@Composable
private fun backupStepText(
    step: BackupStep,
): String = when (step) {
    BackupStep.Preparing -> stringResource(Res.string.pref_item_automatic_backups_step_preparing)
    BackupStep.OpeningRepository -> stringResource(Res.string.pref_item_automatic_backups_step_opening_repository)
    BackupStep.ExportingVault -> stringResource(Res.string.pref_item_automatic_backups_step_exporting_vault)
    BackupStep.ScanningAttachments -> stringResource(Res.string.pref_item_automatic_backups_step_scanning_attachments)
    BackupStep.BackingUpAttachments -> stringResource(Res.string.pref_item_automatic_backups_step_backing_up_attachments)
    BackupStep.WritingIndex -> stringResource(Res.string.pref_item_automatic_backups_step_writing_index)
    BackupStep.WritingSnapshot -> stringResource(Res.string.pref_item_automatic_backups_step_writing_snapshot)
    BackupStep.ApplyingRetention -> stringResource(Res.string.pref_item_automatic_backups_step_applying_retention)
}

@Composable
private fun backupProgressDetailsText(
    details: BackupRunProgressDetails,
): String? {
    val downloaded = details.downloadedBytes
    val total = details.totalBytes
    if (downloaded != null || total != null) {
        val downloadedText = downloaded
            ?.let(::humanReadableByteCountSI)
            ?: "--"
        val totalText = total
            ?.let(::humanReadableByteCountSI)
            ?: "--"
        return stringResource(
            Res.string.pref_item_automatic_backups_progress_bytes,
            downloadedText,
            totalText,
        )
    }

    val processed = details.itemsProcessed
    val items = details.itemsTotal
    return if (processed != null && items != null && items > 0) {
        stringResource(
            Res.string.pref_item_automatic_backups_progress_items,
            processed,
            items,
        )
    } else {
        null
    }
}

private fun BackupRunProgressDetails.progressPercentage(): Float? {
    val downloaded = downloadedBytes
    val total = totalBytes
    if (downloaded != null && total != null && total > 0L) {
        return (downloaded.toDouble() / total.toDouble())
            .toFloat()
            .coerceIn(0f..1f)
    }

    val processed = itemsProcessed
    val items = itemsTotal
    if (processed != null && items != null && items > 0) {
        return (processed.toDouble() / items.toDouble())
            .toFloat()
            .coerceIn(0f..1f)
    }

    return null
}
