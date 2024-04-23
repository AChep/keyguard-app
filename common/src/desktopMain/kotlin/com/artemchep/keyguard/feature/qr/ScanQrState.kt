package com.artemchep.keyguard.feature.qr

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

@Immutable
data class ScanQrState(
    val contentFlow: StateFlow<Content>,
    val onSuccessFlow: Flow<String>,
    val filePickerIntentFlow: Flow<FilePickerIntent<*>> = emptyFlow(),
) {
    data class Content(
        val onSelectFile: () -> Unit,
    )
}
