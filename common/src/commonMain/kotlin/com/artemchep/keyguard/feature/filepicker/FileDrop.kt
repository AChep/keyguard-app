package com.artemchep.keyguard.feature.filepicker

import androidx.compose.runtime.Immutable

/**
 * A file drop target: the [text] shown while a file hovers it,
 * and the handler of the dropped file.
 */
@Immutable
data class FileDrop(
    val text: String,
    val onFileDrop: (FilePickerResult) -> Unit,
)
