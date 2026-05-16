package com.artemchep.keyguard.feature.filepicker

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.ui.CollectedEffect
import kotlinx.coroutines.flow.Flow

@Composable
actual fun FilePickerEffect(
    flow: Flow<FilePickerIntent<*>>,
) {
    CollectedEffect(flow) { intent ->
        when (intent) {
            is FilePickerIntent.NewDocument -> intent.onResult(null)
            is FilePickerIntent.OpenDocument -> intent.onResult(null)
        }
    }
}
