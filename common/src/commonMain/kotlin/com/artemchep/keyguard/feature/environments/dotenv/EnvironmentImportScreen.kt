package com.artemchep.keyguard.feature.environments.dotenv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentDotenv
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cancel
import com.artemchep.keyguard.res.environments_import
import com.artemchep.keyguard.res.environments_import_apply
import com.artemchep.keyguard.res.environments_import_error_parse
import com.artemchep.keyguard.res.environments_import_file
import com.artemchep.keyguard.res.environments_import_note
import com.artemchep.keyguard.res.environments_import_preview_title
import com.artemchep.keyguard.res.environments_import_text
import com.artemchep.keyguard.ui.ConcealedFlatTextField
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private const val IMPORT_BUFFER_SIZE = 4096L

private const val IMPORT_TEXT_MAX_LINES = 10

@Composable
fun EnvironmentImportScreen(
    fileUri: String?,
    transmitter: RouteResultTransmitter<String>,
) {
    // The text holds secrets, so it lives only
    // in the composition.
    var text by remember { mutableStateOf("") }
    var textRevision by remember { mutableIntStateOf(0) }
    var fileFailed by remember { mutableStateOf(false) }
    val definition = remember(text) {
        parseEnvironmentImport(text)
    }

    val onRead = { newText: String ->
        text = newText
        textRevision += 1
        fileFailed = false
    }
    val onFailure = {
        fileFailed = true
    }
    val onChooseFile = rememberEnvironmentImportFilePicker(
        initialFileUri = fileUri,
        onRead = onRead,
        onFailure = onFailure,
    )

    val updatedNavigationController by rememberUpdatedState(LocalNavigationController.current)
    fun deliver(result: String?) {
        if (result != null) {
            transmitter(result)
        }
        updatedNavigationController.queue(NavigationIntent.Pop)
    }

    Dialog(
        icon = icon(Icons.Outlined.FileUpload),
        title = {
            Text(stringResource(Res.string.environments_import))
        },
        content = {
            EnvironmentImportContent(
                text = TextFieldModel(
                    text = text,
                    textRevision = textRevision,
                    onChange = {
                        text = it
                        fileFailed = false
                    },
                ),
                definition = definition,
                invalid = fileFailed || (definition == null && text.isNotBlank()),
                onChooseFile = onChooseFile,
            )
        },
        actions = {
            TextButton(
                onClick = {
                    deliver(null)
                },
            ) {
                Text(stringResource(Res.string.cancel))
            }
            TextButton(
                // Replacing every variable with nothing is
                // never what an import means.
                enabled = definition?.variables?.isNotEmpty() == true,
                onClick = {
                    deliver(text)
                },
            ) {
                Text(stringResource(Res.string.environments_import_apply))
            }
        },
    )
}

@Composable
private fun EnvironmentImportContent(
    text: TextFieldModel,
    definition: EnvironmentDefinition?,
    invalid: Boolean,
    onChooseFile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            text = stringResource(Res.string.environments_import_note),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        ConcealedFlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            label = stringResource(Res.string.environments_import_text),
            value = text,
            maxLines = IMPORT_TEXT_MAX_LINES,
        )
        EnvironmentImportFileButton(
            onClick = onChooseFile,
        )
        if (invalid) {
            FlatSimpleNote(
                type = SimpleNote.Type.ERROR,
                text = stringResource(Res.string.environments_import_error_parse),
            )
        }
        val variables = definition?.variables
        if (!invalid && !variables.isNullOrEmpty()) {
            Spacer(
                modifier = Modifier
                    .height(8.dp),
            )
            EnvironmentImportPreview(
                names = variables.map { it.name },
            )
        }
    }
}

@Composable
private fun EnvironmentImportFileButton(
    onClick: () -> Unit,
) {
    TextButton(
        modifier = Modifier
            .padding(horizontal = Dimens.buttonHorizontalPadding),
        onClick = onClick,
    ) {
        Icon(
            modifier = Modifier
                .size(ButtonDefaults.IconSize),
            imageVector = Icons.Outlined.FileOpen,
            contentDescription = null,
        )
        Spacer(
            modifier = Modifier
                .width(ButtonDefaults.IconSpacing),
        )
        Text(
            text = stringResource(Res.string.environments_import_file),
        )
    }
}

@Composable
private fun EnvironmentImportPreview(
    names: List<String>,
) {
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        text = stringResource(Res.string.environments_import_preview_title),
        style = MaterialTheme.typography.labelLarge,
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    // Only the names: the values stay concealed.
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        text = names.joinToString(separator = "\n"),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Returns a callback that asks for a `.env` file and reads it, up
 * to the size limit of an environment.
 */
@Composable
private fun rememberEnvironmentImportFilePicker(
    initialFileUri: String?,
    onRead: (String) -> Unit,
    onFailure: () -> Unit,
): () -> Unit {
    val fileService = koinInject<FileService>()
    val scope = rememberCoroutineScope()
    val updatedOnRead by rememberUpdatedState(onRead)
    val updatedOnFailure by rememberUpdatedState(onFailure)
    val read = remember(fileService) {
        // lambda
        { uri: String ->
            scope.launch {
                try {
                    val text = withContext(Dispatchers.Default) {
                        readEnvironmentImport(fileService, uri)
                    }
                    updatedOnRead(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    updatedOnFailure()
                }
            }
            Unit
        }
    }
    // A file dropped onto the editor is read right away.
    LaunchedEffect(initialFileUri) {
        if (initialFileUri != null) {
            read(initialFileUri)
        }
    }

    val filePickerIntentFlow = remember {
        MutableSharedFlow<FilePickerIntent<*>>(extraBufferCapacity = 1)
    }
    FilePickerEffect(filePickerIntentFlow)
    return remember(read) {
        // lambda
        {
            val intent = FilePickerIntent.OpenDocument { result ->
                if (result != null) {
                    read(result.uri.toString())
                }
            }
            filePickerIntentFlow.tryEmit(intent)
        }
    }
}

/** Returns the parsed environment, or `null` if the text is not valid. */
private fun parseEnvironmentImport(text: String): EnvironmentDefinition? = try {
    EnvironmentDotenv.parse(text)
} catch (_: IllegalArgumentException) {
    null
}

private fun readEnvironmentImport(files: FileService, uri: String): String =
    files.readFromFile(uri).use { source ->
        val buffer = Buffer()
        while (!source.exhausted() && buffer.size <= EnvironmentFormat.MAX_PAYLOAD_BYTES) {
            source.readAtMostTo(buffer, IMPORT_BUFFER_SIZE)
        }
        require(buffer.size <= EnvironmentFormat.MAX_PAYLOAD_BYTES)
        buffer.readByteArray().decodeToString(throwOnInvalidSequence = true)
    }
