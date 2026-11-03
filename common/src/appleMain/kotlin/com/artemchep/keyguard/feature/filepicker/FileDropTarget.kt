package com.artemchep.keyguard.feature.filepicker

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun Modifier.platformFileDropTarget(
    enabled: Boolean,
    multiple: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
    onTooManyFiles: () -> Unit,
    onFilesDrop: (List<FilePickerResult>) -> Unit,
): Modifier = this

@Composable
internal actual fun Modifier.fileDragMonitor(
    enabled: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
): Modifier = this
