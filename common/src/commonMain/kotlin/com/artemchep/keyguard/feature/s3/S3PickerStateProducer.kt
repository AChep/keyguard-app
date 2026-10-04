package com.artemchep.keyguard.feature.s3

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.ListS3Directory
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.remotepicker.RemotePickerEntry
import com.artemchep.keyguard.feature.remotepicker.RemotePickerState
import com.artemchep.keyguard.feature.remotepicker.remotePickerStateFlow
import com.artemchep.keyguard.util.s3.isValidS3ObjectKey
import kotlinx.coroutines.flow.Flow
import org.koin.compose.currentKoinScope

@Composable
fun produceS3PickerState(
    route: S3PickerRoute,
    transmitter: RouteResultTransmitter<S3PickerResult>,
): RemotePickerState = with(currentKoinScope()) {
    produceS3PickerState(
        route = route,
        transmitter = transmitter,
        listS3Directory = get(),
    )
}

@Composable
fun produceS3PickerState(
    route: S3PickerRoute,
    transmitter: RouteResultTransmitter<S3PickerResult>,
    listS3Directory: ListS3Directory,
): RemotePickerState = produceScreenState(
    key = "s3_picker",
    initial = RemotePickerState(
        path = route.args.initialPath,
        breadcrumbs = emptyList(),
        content = Loadable.Loading,
        fileName = null,
        fileNameError = null,
        onConfirm = null,
        onRefresh = {},
    ),
    args = arrayOf(
        route,
        listS3Directory,
    ),
) {
    s3PickerStateFlow(
        route = route,
        listS3Directory = listS3Directory,
        onComplete = { key ->
            transmitter(S3PickerResult(key))
            navigatePopSelf()
        },
    )
}

private fun s3PickerStateFlow(
    route: S3PickerRoute,
    listS3Directory: ListS3Directory,
    onComplete: (String) -> Unit,
): Flow<RemotePickerState> = remotePickerStateFlow(
    mode = route.args.mode,
    initialPath = route.args.initialPath.trim('/'),
    initialFileName = route.args.initialFileName,
    // S3 keys are case-sensitive.
    ignoreNameCase = false,
    load = { path ->
        listS3Directory(
            ListS3Directory.Request(
                bucket = route.args.bucket,
                accessKey = route.args.accessKey,
                prefix = s3PickerPrefix(path),
            ),
        ).attempt().bind()
            .map { children -> children.map(::s3PickerEntry) }
    },
    folderResult = ::s3PickerPrefix,
    fileResult = { key -> key },
    onComplete = onComplete,
)

/** Converts a picker path, which has no trailing slash, to a listing prefix. */
internal fun s3PickerPrefix(
    path: String,
): String = if (path.isEmpty()) "" else "$path/"

internal fun s3PickerEntry(
    child: ListS3Directory.Child,
) = RemotePickerEntry(
    path = child.key.removeSuffix("/"),
    name = child.name,
    isFolder = child.isFolder,
    size = child.size,
    // Keys written by other tools may not be addressable as objects.
    isAddressable = child.isFolder || isValidS3ObjectKey(child.key),
)
