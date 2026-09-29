package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupRunProgress
import com.artemchep.keyguard.common.service.backup.BackupRunProgressDetails
import com.artemchep.keyguard.common.service.backup.BackupSnapshotStats
import com.artemchep.keyguard.common.service.backup.BackupStatus
import com.artemchep.keyguard.common.service.backup.BackupStep
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_automatic_backups_wizard_password_mismatch_error
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.ui.theme.GlobalExpressive
import com.artemchep.keyguard.ui.theme.LocalExpressive
import com.artemchep.keyguard.ui.theme.plainDarkColorScheme
import com.artemchep.keyguard.ui.theme.plainLightColorScheme
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** A service-free host shared by IDE previews and Desktop render checks. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AutomaticBackupsPreview(
    dark: Boolean = false,
    expressive: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = if (dark) plainDarkColorScheme() else plainLightColorScheme()
    MaterialExpressiveTheme(colorScheme = scheme) {
        androidx.compose.material.MaterialTheme(
            colors = (if (dark) darkColors() else lightColors()).copy(
                primary = scheme.primary,
                onPrimary = scheme.onPrimary,
                secondary = scheme.secondary,
                onSecondary = scheme.onSecondary,
            ),
        ) {
            CompositionLocalProvider(
                GlobalExpressive provides expressive,
                LocalExpressive provides expressive,
                LocalSurfaceColor provides scheme.surface,
            ) {
                Surface(content = content)
            }
        }
    }
}

internal object AutomaticBackupsPreviewData {
    const val CUSTOM_MAX_SNAPSHOTS = 17
    val localStore = BackupStoreConfig.Local("/Users/example/Backups")
    val webDavStore = BackupStoreConfig.WebDav("https://backup.example.com/keyguard", username = "alex")
    val config = BackupConfig(store = localStore)
    val startedAt = Instant.parse("2026-05-28T10:42:00Z")
    private val finishedAt = Instant.parse("2026-05-28T10:42:11Z")

    val success = BackupStatus(
        lastStartedAt = startedAt,
        lastFinishedAt = finishedAt,
        lastSnapshotId = "2026-05-28T10-42-11Z-a1b2",
        lastStats = BackupSnapshotStats(
            cipherCount = 128,
            attachmentCount = 12,
            newBlobCount = 2,
            reusedBlobCount = 10,
        ),
        lastChangedAt = startedAt,
        lastSuccessfulBackupAt = finishedAt,
    )
    val failure = BackupStatus(
        lastStartedAt = startedAt,
        lastFinishedAt = finishedAt,
        lastErrorMessage = "The backup destination is unavailable. Check the connection and try again.",
        changeGeneration = 1,
    )
    val skipped = BackupStatus(lastStartedAt = startedAt, lastSkippedReason = "vault_locked")
    val running = BackupStatus(
        currentRun = BackupRunProgress(
            runId = "preview-run",
            trigger = "manual",
            startedAt = startedAt,
            step = BackupStep.BackingUpAttachments,
            details = BackupRunProgressDetails(downloadedBytes = 4_000_000, totalBytes = 10_000_000),
        ),
    )
    val preparing = BackupStatus(
        currentRun = BackupRunProgress(
            runId = "preview-run",
            trigger = "manual",
            startedAt = startedAt,
            step = BackupStep.Preparing,
        ),
    )

    fun setup(
        step: BackupSetupStep = BackupSetupStep.Destination,
        store: BackupStoreConfig = localStore,
        password: String = "",
        confirmationPassword: String = password,
        saving: Boolean = false,
        error: String? = null,
        confirmingDiscard: Boolean = false,
    ) = BackupSetupData(
        config = config.copy(store = store, password = password.takeIf(String::isNotEmpty)?.let(::Password)),
        step = step,
        confirmationPassword = confirmationPassword,
        isSaving = saving,
        error = error,
        hasEdits = confirmingDiscard,
        confirmingDiscard = confirmingDiscard,
    )

    fun settings(enabled: Boolean = true, status: BackupStatus = success) = AutomaticBackupsSettingsState(
        config = config.copy(enabled = enabled),
        status = status,
        onSetupClick = {},
        onRetentionChange = {},
        onRunNow = {},
        onDisableClick = {},
    )

    val dateFormatter = object : DateFormatter {
        override fun formatDateTime(instant: Instant) = instant.toLocalDateTime(TimeZone.UTC).toString()
        override fun formatDate(instant: Instant) = instant.toLocalDateTime(TimeZone.UTC).date.toString()
        override suspend fun formatDateShort(date: LocalDate) = date.toString()
        override fun formatDateMedium(date: LocalDate) = date.toString()
        override fun formatTimeShort(time: LocalTime) = time.toString()
    }
}

@Composable
internal fun automaticBackupsSetupPreviewState(data: BackupSetupData): AutomaticBackupsSetupState {
    val editable = data.isEditable
    return AutomaticBackupsSetupState(
        data = data,
        password = TextFieldModel(
            text = data.config.password?.value.orEmpty(),
            onChange = { _: String -> }.takeIf { editable },
        ),
        confirmationPassword = TextFieldModel(
            text = data.confirmationPassword,
            error = stringResource(Res.string.pref_item_automatic_backups_wizard_password_mismatch_error)
                .takeIf { data.showPasswordMismatch },
            onChange = { _: String -> }.takeIf { editable },
        ),
        filePickerIntentFlow = emptyFlow(),
        onStoreKindChange = {},
        onLocationClick = {}.takeIf { editable },
        onIncludeAttachmentsChange = { _: Boolean -> }.takeIf { editable },
        onRetentionChange = { _: Int -> }.takeIf { editable },
        onBack = {},
        onContinue = {}.takeIf { data.canContinue },
        onDiscard = {},
        onDismissDiscard = {},
    )
}
