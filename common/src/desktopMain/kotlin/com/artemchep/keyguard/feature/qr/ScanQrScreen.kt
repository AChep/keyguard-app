package com.artemchep.keyguard.feature.qr

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import org.jetbrains.compose.resources.stringResource

@Composable
fun ScanQrScreen(
    modifier: Modifier = Modifier,
    transmitter: RouteResultTransmitter<String>,
) {
    val loadableState = produceScanQrState()
    when (loadableState) {
        is Loadable.Ok -> {
            val state = loadableState.value
            CollectedEffect(state.onSuccessFlow) { rawValue ->
                // Notify that we have successfully logged in, and that
                // the caller can now decide what to do.
                transmitter.invoke(rawValue)
            }
            FilePickerEffect(
                flow = state.filePickerIntentFlow,
            )
        }

        else -> {
            // Do nothing.
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldColumn(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(stringResource(Res.string.scanqr_title))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        when (loadableState) {
            is Loadable.Ok -> {
                val contentState = loadableState.value.contentFlow
                    .collectAsState()
                Text(
                    modifier = Modifier
                        .padding(horizontal = Dimens.horizontalPadding),
                    text = stringResource(Res.string.scanqr_load_from_image_note),
                )
                Spacer(
                    modifier = Modifier
                        .height(Dimens.verticalPadding),
                )
                Button(
                    modifier = Modifier
                        .padding(horizontal = Dimens.horizontalPadding),
                    onClick = {
                        contentState.value.onSelectFile()
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.select_file),
                    )
                }
            }

            is Loadable.Loading -> {
                // Do nothing
            }
        }
    }
}
