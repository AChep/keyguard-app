package com.artemchep.keyguard.feature.s3

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.remotepicker.RemotePickerContent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.s3_picker_header_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun S3PickerScreen(
    route: S3PickerRoute,
    transmitter: RouteResultTransmitter<S3PickerResult>,
) {
    val state = produceS3PickerState(
        route = route,
        transmitter = transmitter,
    )
    RemotePickerContent(
        state = state,
        mode = route.args.mode,
        title = stringResource(Res.string.s3_picker_header_title),
    )
}
