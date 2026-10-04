package com.artemchep.keyguard.feature.webdav

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.remotepicker.RemotePickerContent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.webdav_picker_header_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun WebDavPickerScreen(
    route: WebDavPickerRoute,
    transmitter: RouteResultTransmitter<WebDavPickerResult>,
) {
    val state = produceWebDavPickerState(
        route = route,
        transmitter = transmitter,
    )
    RemotePickerContent(
        state = state,
        mode = route.args.mode,
        title = stringResource(Res.string.webdav_picker_header_title),
    )
}
