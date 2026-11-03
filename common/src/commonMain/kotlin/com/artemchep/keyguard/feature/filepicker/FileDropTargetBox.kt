package com.artemchep.keyguard.feature.filepicker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.file_drop_single_file_only
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Accepts dropped files. If [multiple] is `false`, a drop of more
 * than one file is rejected and [onTooManyFiles] is called instead.
 * [onFilesDrop] never gets an empty list.
 */
@Composable
internal expect fun Modifier.platformFileDropTarget(
    enabled: Boolean,
    multiple: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
    onTooManyFiles: () -> Unit,
    onFilesDrop: (List<FilePickerResult>) -> Unit,
): Modifier

/**
 * Accepts a single dropped file; a drop of several files is
 * rejected with a message.
 */
@Composable
internal fun Modifier.fileDropTarget(
    enabled: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
    onFileDrop: (FilePickerResult) -> Unit,
): Modifier = platformFileDropTarget(
    enabled = enabled,
    multiple = false,
    onDragActiveChange = onDragActiveChange,
    onTooManyFiles = rememberTooManyFilesHandler(),
    onFilesDrop = { files ->
        onFileDrop(files.single())
    },
)

@Composable
internal fun Modifier.filesDropTarget(
    enabled: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
    onFilesDrop: (List<FilePickerResult>) -> Unit,
): Modifier = platformFileDropTarget(
    enabled = enabled,
    multiple = true,
    onDragActiveChange = onDragActiveChange,
    onTooManyFiles = {
        // Do nothing
    },
    onFilesDrop = onFilesDrop,
)

@Composable
private fun rememberTooManyFilesHandler(): () -> Unit {
    val showMessage = koinInject<ShowMessage>()
    val title = stringResource(Res.string.file_drop_single_file_only)
    return remember(showMessage, title) {
        {
            val msg = ToastMessage(
                type = ToastMessage.Type.ERROR,
                title = title,
            )
            showMessage.copy(msg)
        }
    }
}

@Composable
internal expect fun Modifier.fileDragMonitor(
    enabled: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
): Modifier

@Composable
internal fun FileDropTargetBox(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    propagateMinConstraints: Boolean = false,
    onFileDrop: (FilePickerResult) -> Unit,
    content: @Composable BoxScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit,
) {
    var dragActive by remember {
        mutableStateOf(false)
    }
    Box(
        modifier = modifier
            .fileDropTarget(
                enabled = enabled,
                onDragActiveChange = { isActive ->
                    dragActive = isActive
                },
                onFileDrop = onFileDrop,
            ),
        propagateMinConstraints = propagateMinConstraints,
        content = {
            content()
            if (enabled && dragActive) {
                overlay()
            }
        },
    )
}

@Composable
internal fun FileDropOverlay(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.large,
            )
            .background(
                color = MaterialTheme.colorScheme.primaryContainer
                    .copy(alpha = DisabledEmphasisAlpha)
                    // make it a bit less transparent
                    .compositeOver(
                        MaterialTheme.colorScheme.surface
                            .copy(alpha = DisabledEmphasisAlpha)
                    ),
                shape = MaterialTheme.shapes.large,
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
internal fun FileDropOverlay(
    text: String,
    modifier: Modifier = Modifier,
) {
    FileDropOverlay(
        modifier = modifier,
    ) {
        Text(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(24.dp),
            text = text,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}
