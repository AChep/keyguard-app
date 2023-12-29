package com.artemchep.keyguard.feature.filepicker

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

@Composable
expect fun FilePickerEffect(
    flow: Flow<FilePickerIntent<*>>,
)
