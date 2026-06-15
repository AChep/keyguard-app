package com.artemchep.keyguard.feature.qr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.ui.CollectedEffect

@Composable
fun produceLoadQrStateWithEffects(
    onValueChange: ((String) -> Unit)? = null,
): LoadQrState.Content? {
    val updatedOnValueChange by rememberUpdatedState(onValueChange)

    // Load the QR loading state producer and report back
    // whether it is available or not.
    val loadableState = produceLoadQrState()
    when (loadableState) {
        is Loadable.Ok -> {
            val state = loadableState.value
            CollectedEffect(state.onSuccessFlow) { rawValue ->
                // Notify that we have successfully scanned the code, and that
                // the caller can now decide what to do.
                updatedOnValueChange?.invoke(rawValue)
            }
            FilePickerEffect(
                flow = state.filePickerIntentFlow,
            )

            val contentState = state.contentFlow
                .collectAsState()
            return contentState.value
        }

        else -> {
            // Do nothing.
        }
    }
    return null
}
