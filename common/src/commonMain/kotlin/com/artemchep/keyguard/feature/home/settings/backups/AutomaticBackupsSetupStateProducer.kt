package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.backup.BackupConfigRepository
import com.artemchep.keyguard.common.usecase.TestBackupLocation
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_automatic_backups_wizard_password_mismatch_error
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.plus
import org.koin.compose.currentKoinScope

@Composable
internal fun produceAutomaticBackupsSetupState(): Loadable<AutomaticBackupsSetupState> =
    with(currentKoinScope()) {
        val repository = get<BackupConfigRepository>()
        val testLocation = get<TestBackupLocation>()
        produceScreenState(
            key = "automatic_backups_setup",
            initial = Loadable.Loading,
            args = arrayOf(repository, testLocation),
        ) {
            automaticBackupsSetupStateProducer(repository, testLocation)
        }
    }

private suspend fun RememberStateFlowScope.automaticBackupsSetupStateProducer(
    repository: BackupConfigRepository,
    testLocation: TestBackupLocation,
): Flow<Loadable<AutomaticBackupsSetupState>> {
    val initial = repository.getConfig().first()
    val editor = AutomaticBackupsSetupEditor(
        initial = initial,
        scope = screenScope + Dispatchers.Main.immediate,
        verify = { testLocation(it).invoke() },
        save = { repository.setConfig(it).invoke() },
        onClose = ::navigatePopSelf,
    )
    val filePicker = EventFlow<FilePickerIntent<*>>()
    val mismatchError = translate(Res.string.pref_item_automatic_backups_wizard_password_mismatch_error)
    interceptBackPress(flowOf(editor::back))

    fun pickLocation() {
        if (editor.state.value.isSaving) return
        pickBackupLocation(
            current = editor.state.value.config.store,
            filePicker = filePicker,
            write = true,
            receive = editor.destinationReceiver(),
        )
    }

    val onDiscard = { editor.confirmDiscard(true) }
    val onDismissDiscard = { editor.confirmDiscard(false) }
    return editor.state.map { data ->
        val enabled = data.isEditable
        Loadable.Ok(
            AutomaticBackupsSetupState(
                data = data,
                password = TextFieldModel(
                    id = "backup_password",
                    text = data.config.password?.value.orEmpty(),
                    onChange = editor::setPassword.takeIf { enabled },
                ),
                confirmationPassword = TextFieldModel(
                    id = "backup_password_confirmation",
                    text = data.confirmationPassword,
                    error = mismatchError.takeIf { data.showPasswordMismatch },
                    onChange = editor::setConfirmationPassword.takeIf { enabled },
                ),
                filePickerIntentFlow = filePicker,
                onStoreKindChange = editor::selectStore,
                onLocationClick = ::pickLocation.takeIf { enabled },
                onIncludeAttachmentsChange = editor::setIncludeAttachments.takeIf { enabled },
                onRetentionChange = editor::setRetention.takeIf { enabled },
                onBack = editor::back,
                onContinue = editor::next.takeIf { data.canContinue },
                onDiscard = onDiscard,
                onDismissDiscard = onDismissDiscard,
            ),
        )
    }
}
