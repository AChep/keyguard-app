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
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha

@Composable
internal expect fun Modifier.fileDropTarget(
    enabled: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
    onFileDrop: (FilePickerResult) -> Unit,
): Modifier

@Composable
internal expect fun Modifier.fileDragMonitor(
    enabled: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
): Modifier

@Composable
internal fun FileDropTargetBox(
    modifier: Modifier = Modifier,
    enabled: Boolean,
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
