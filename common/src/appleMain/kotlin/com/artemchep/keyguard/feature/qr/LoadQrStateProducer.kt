package com.artemchep.keyguard.feature.qr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.common.model.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

@Composable
actual fun produceLoadQrState(): Loadable<LoadQrState> = remember {
    val content = LoadQrState.Content(
        onSelectFile = {},
    )
    val state = LoadQrState(
        contentFlow = MutableStateFlow(content),
        onSuccessFlow = emptyFlow(),
        filePickerIntentFlow = emptyFlow(),
    )
    Loadable.Ok(state)
}
