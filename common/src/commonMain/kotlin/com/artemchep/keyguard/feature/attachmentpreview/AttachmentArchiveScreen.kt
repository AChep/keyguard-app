package com.artemchep.keyguard.feature.attachmentpreview

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.ProvideScaffoldLocalValues
import com.artemchep.keyguard.ui.breadcrumbsItem
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
internal fun AttachmentArchiveContent(
    content: AttachmentPreviewContent.Archive,
    busy: Boolean,
    padding: PaddingValues,
    listState: LazyListState,
) {
    ProvideScaffoldLocalValues(expressive = true) {
        LazyColumn(state = listState, contentPadding = padding) {
            if (content.breadcrumbs.isNotEmpty()) {
                breadcrumbsItem(
                    breadcrumbs = content.breadcrumbs,
                )
            }
            if (content.items.isEmpty()) {
                item("empty") {
                    EmptyView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        text = {
                            Text(
                                text = stringResource(Res.string.attachment_archive_empty),
                            )
                        },
                    )
                }
            }
            itemsIndexed(
                items = content.items,
                key = { _, item -> item.id },
            ) { index, item ->
                AttachmentArchiveItem(
                    item = item,
                    busy = busy,
                    shapeState = getShapeState(content.items, index) { _, _ -> true },
                )
            }
        }
    }
}

@Composable
private fun AttachmentArchiveItem(
    item: AttachmentArchiveItem,
    busy: Boolean,
    shapeState: Int,
) {
    val onOpen = item.onOpen.takeUnless { busy }
    val onSave = item.onSave.takeUnless { busy }
    val summary = item.summary(canSave = true)
    FlatItemSimpleExpressive(
        shapeState = shapeState,
        title = {
            Text(
                text = item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = summary?.let {
            // composable
            {
                Text(
                    text = summary,
                )
            }
        },
        leading = {
            Icon(
                imageVector = if (item.isDirectory) {
                    Icons.Outlined.Folder
                } else {
                    Icons.Outlined.Description
                },
                contentDescription = null,
            )
        },
        trailing = item.onSave?.let {
            // composable
            {
                IconButton(
                    onClick = { onSave?.invoke() },
                    enabled = onSave != null,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SaveAlt,
                        contentDescription = stringResource(Res.string.attachment_archive_save),
                    )
                }
            }
        },
        onClick = onOpen,
        // A save-only entry stays at full emphasis.
        enabled = onOpen != null || onSave != null,
    )
}

/**
 * The size and status line of an archive entry, or `null` if there is
 * nothing to show. Set [canSave] to `false` if the platform hides saving.
 */
@Composable
fun AttachmentArchiveItem.summary(
    canSave: Boolean,
): String? {
    val unavailable = !isDirectory && onOpen == null && (!canSave || onSave == null)
    return listOfNotNull(
        size?.let(::humanReadableByteCountSI),
        stringResource(Res.string.attachment_archive_encrypted).takeIf { isEncrypted },
        stringResource(Res.string.attachment_archive_unavailable).takeIf { unavailable },
    ).joinToString(" · ").takeIf { it.isNotEmpty() }
}

/** Android and desktop effects: the archive save picker and the password prompt. */
@Composable
internal fun AttachmentArchiveEffects(state: AttachmentPreviewState) {
    val events = remember { EventFlow<FilePickerIntent<*>>() }
    val fileService: FileService = koinInject()
    val showMessage: ShowMessage = koinInject()
    val windowScope: WindowCoroutineScope = koinInject()
    FilePickerEffect(events)
    val export = state.export
    LaunchedEffect(export) {
        if (export == null) return@LaunchedEffect
        val onCleanupFailure: suspend () -> Unit = {
            showMessage.copy(ToastMessage(
                type = ToastMessage.Type.ERROR,
                title = getString(Res.string.attachment_archive_error_export_cleanup),
            ))
        }
        saveAttachmentArchiveExport(export, fileService, onCleanupFailure) {
            selectAttachmentArchiveDestination(export.name, events::emit) { abandoned ->
                // The picker may return after the composition and its job have gone away.
                windowScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    discardAttachmentArchiveDestination(abandoned, fileService, onCleanupFailure)
                }
            }
        }
    }
    state.password?.let { AttachmentArchivePasswordDialog(it) }
}

@Composable
private fun AttachmentArchivePasswordDialog(prompt: AttachmentArchivePassword) {
    var password by remember(prompt) { mutableStateOf("") }
    val submit = {
        if (password.isNotEmpty()) {
            prompt.onSubmit(password)
            password = ""
        }
    }
    val requester = remember { FocusRequester2() }
    AlertDialog(
        onDismissRequest = prompt.onCancel,
        title = { Text(stringResource(Res.string.attachment_archive_password)) },
        text = {
            PasswordFlatTextField(
                modifier = Modifier
                    .focusRequester2(requester),
                value = TextFieldModel(
                    text = password,
                    error = stringResource(Res.string.attachment_archive_error_password)
                        .takeIf { prompt.incorrect },
                    onChange = { password = it },
                ),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { submit() },
                ),
            )
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty(),
                onClick = submit,
            ) {
                Text(stringResource(Res.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = prompt.onCancel) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
    LaunchedEffect(requester) {
        // Let the dialog attach before focusing the field.
        delay(PASSWORD_FOCUS_DELAY_MS)
        requester.requestFocus()
    }
}

private const val PASSWORD_FOCUS_DELAY_MS = 80L
