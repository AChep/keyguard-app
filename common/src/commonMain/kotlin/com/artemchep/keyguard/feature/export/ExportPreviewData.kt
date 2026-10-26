package com.artemchep.keyguard.feature.export

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.common.service.export.model.ExportFormat
import com.artemchep.keyguard.common.service.permission.Permission
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_automatic_backups_wizard_password_mismatch_error
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource

/** The inputs of a service-free export state for previews and render checks. */
internal data class ExportPreviewData(
    val step: ExportStep = ExportStep.Format,
    val format: ExportFormat = ExportFormat.Zip,
    val itemsCount: Int = 128,
    val attachmentsSize: String? = "12 MB",
    val attachmentsEnabled: Boolean = false,
    val password: String = "",
    val confirmation: String = "",
    val permissionDeclined: Boolean = false,
) {
    val passwordMatches: Boolean
        get() = password == confirmation

    companion object {
        fun review() = ExportPreviewData(
            step = ExportStep.Review,
            attachmentsEnabled = true,
            password = "example-password",
            confirmation = "example-password",
        )
    }
}

@Composable
internal fun exportPreviewState(
    data: ExportPreviewData,
): ExportState {
    val format = exportPreviewFormat(data.format)
    val mismatchError = stringResource(Res.string.pref_item_automatic_backups_wizard_password_mismatch_error)
    return remember(data, format, mismatchError) {
        val wizard = ExportWizardData(
            step = data.step,
            itemsCount = data.itemsCount,
            passwordValid = data.password.isNotEmpty(),
            passwordMatches = data.passwordMatches,
        ).toState(MutableStateFlow(data.step))
        val confirmationError = mismatchError
            .takeIf { data.confirmation.isNotEmpty() && !data.passwordMatches }
        ExportState(
            wizardFlow = MutableStateFlow(wizard),
            itemsFlow = MutableStateFlow(data.items()),
            attachmentsFlow = MutableStateFlow(data.attachments()),
            filterFlow = MutableStateFlow(ExportState.Filter(items = emptyList())),
            formatFlow = MutableStateFlow(format),
            passwordFlow = MutableStateFlow(passwordState(data.password)),
            confirmationPasswordFlow = MutableStateFlow(passwordState(data.confirmation, confirmationError)),
            contentFlow = MutableStateFlow(data.content()),
        )
    }
}

@Composable
private fun exportPreviewFormat(
    format: ExportFormat,
) = ExportState.Format(
    title = stringResource(format.titleRes()),
    note = stringResource(format.noteRes()),
    attachmentsNote = stringResource(format.attachmentsNoteRes()),
    passwordLabel = stringResource(format.passwordLabelRes()),
    confirmPasswordLabel = stringResource(format.confirmPasswordLabelRes()),
    options = ExportFormat.entries.map { entry ->
        FlatItemAction(
            id = entry.extension,
            icon = entry.icon(),
            title = entry.titleRes().wrap(),
            selected = entry == format,
        )
    },
)

private fun ExportPreviewData.items() = ExportState.Items(
    revision = 0,
    list = emptyList(),
    count = itemsCount,
    onView = {},
)

private fun ExportPreviewData.attachments() = ExportState.Attachments(
    revision = 0,
    list = emptyList(),
    size = attachmentsSize,
    count = if (attachmentsSize != null) 12 else 0,
    enabled = attachmentsEnabled,
    onToggle = {}.takeIf { attachmentsSize != null },
)

private fun ExportPreviewData.content(): ExportState.Content {
    val writePermission = if (permissionDeclined) {
        PermissionState.Declined(
            permission = Permission.WRITE_EXTERNAL_STORAGE,
            ask = {},
            openSettings = {},
        )
    } else {
        PermissionState.Granted
    }
    val canExport = password.isNotEmpty() &&
            passwordMatches &&
            writePermission is PermissionState.Granted
    return ExportState.Content(
        writePermission = writePermission,
        onExportClick = {}.takeIf { canExport },
    )
}

private fun passwordState(
    text: String,
    error: String? = null,
) = ExportState.Password(
    model = TextFieldModel(
        text = text,
        error = error,
        onChange = {},
    ),
)
