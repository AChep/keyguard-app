package com.artemchep.keyguard.feature.filepicker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.artemchep.keyguard.ui.OverlayScope
import com.artemchep.keyguard.ui.theme.Dimens
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
 * rejected with a message. A `null` [onFileDrop] disables it.
 */
@Composable
internal fun Modifier.fileDropTarget(
    onDragActiveChange: (Boolean) -> Unit,
    onFileDrop: ((FilePickerResult) -> Unit)?,
): Modifier = platformFileDropTarget(
    enabled = onFileDrop != null,
    multiple = false,
    onDragActiveChange = onDragActiveChange,
    onTooManyFiles = rememberTooManyFilesHandler(),
    onFilesDrop = { files ->
        onFileDrop?.invoke(files.single())
    },
)

/**
 * Accepts dropped files. A `null` [onFilesDrop] disables it.
 */
@Composable
internal fun Modifier.filesDropTarget(
    onDragActiveChange: (Boolean) -> Unit,
    onFilesDrop: ((List<FilePickerResult>) -> Unit)?,
): Modifier = platformFileDropTarget(
    enabled = onFilesDrop != null,
    multiple = true,
    onDragActiveChange = onDragActiveChange,
    onTooManyFiles = {
        // Do nothing
    },
    onFilesDrop = { files ->
        onFilesDrop?.invoke(files)
    },
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

private val DefaultFileDropTextPadding = PaddingValues(24.dp)

/**
 * Accepts a single dropped file onto [content], showing [text] over it,
 * inset by [overlayPadding], while a file hovers it. A `null`
 * [onFileDrop] disables it.
 */
@Composable
internal fun FileDropTargetBox(
    text: String,
    onFileDrop: ((FilePickerResult) -> Unit)?,
    modifier: Modifier = Modifier,
    overlayPadding: PaddingValues = PaddingValues(0.dp),
    textPadding: PaddingValues = DefaultFileDropTextPadding,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    var dragActive by remember {
        mutableStateOf(false)
    }
    Box(
        modifier = modifier
            .fileDropTarget(
                onDragActiveChange = { isActive ->
                    dragActive = isActive
                },
                onFileDrop = onFileDrop,
            ),
        propagateMinConstraints = propagateMinConstraints,
    ) {
        content()
        if (onFileDrop != null && dragActive) {
            FileDropOverlay(
                modifier = Modifier
                    .matchParentSize()
                    .padding(overlayPadding),
                text = text,
                textPadding = textPadding,
            )
        }
    }
}

/**
 * Accepts a single dropped file over the scaffold's content, below the
 * toolbar, showing [text] while a file hovers it. A `null` [onFileDrop]
 * disables it.
 */
@Composable
internal fun OverlayScope.ContentFileDropTarget(
    text: String,
    onFileDrop: ((FilePickerResult) -> Unit)?,
) {
    FileDropTargetBox(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding.value),
        text = text,
        onFileDrop = onFileDrop,
        overlayPadding = PaddingValues(horizontal = Dimens.contentPadding),
    )
}

/**
 * A list item that shows where a dropped file lands, while a file
 * hovers a screen-wide drop target.
 */
@Composable
internal fun FileDropPlaceholder(
    text: String,
    modifier: Modifier = Modifier,
) {
    FileDropOverlay(
        modifier = modifier
            .padding(horizontal = Dimens.buttonHorizontalPadding)
            .fillMaxWidth(),
        text = text,
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
    textPadding: PaddingValues = DefaultFileDropTextPadding,
) {
    FileDropOverlay(
        modifier = modifier,
    ) {
        Text(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(textPadding),
            text = text,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}
