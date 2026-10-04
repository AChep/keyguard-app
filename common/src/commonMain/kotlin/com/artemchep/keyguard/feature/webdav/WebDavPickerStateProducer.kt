package com.artemchep.keyguard.feature.webdav

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.WebDavCredentials
import com.artemchep.keyguard.common.usecase.ListWebDavDirectory
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.remotepicker.RemotePickerEntry
import com.artemchep.keyguard.feature.remotepicker.RemotePickerState
import com.artemchep.keyguard.feature.remotepicker.remotePickerStateFlow
import com.artemchep.keyguard.util.webdav.normalizeWebDavRelativePath
import com.artemchep.keyguard.util.webdav.resolveWebDavResourceUrl
import kotlinx.coroutines.flow.Flow
import org.koin.compose.currentKoinScope

@Composable
fun produceWebDavPickerState(
    route: WebDavPickerRoute,
    transmitter: RouteResultTransmitter<WebDavPickerResult>,
): RemotePickerState = with(currentKoinScope()) {
    produceWebDavPickerState(
        route = route,
        transmitter = transmitter,
        listWebDavDirectory = get(),
    )
}

@Composable
fun produceWebDavPickerState(
    route: WebDavPickerRoute,
    transmitter: RouteResultTransmitter<WebDavPickerResult>,
    listWebDavDirectory: ListWebDavDirectory,
): RemotePickerState = produceScreenState(
    key = "webdav_picker",
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
        listWebDavDirectory,
    ),
) {
    webDavPickerStateFlow(
        route = route,
        listWebDavDirectory = listWebDavDirectory,
        onComplete = { url ->
            transmitter(WebDavPickerResult(url))
            navigatePopSelf()
        },
    )
}

private fun webDavPickerStateFlow(
    route: WebDavPickerRoute,
    listWebDavDirectory: ListWebDavDirectory,
    onComplete: (String) -> Unit,
): Flow<RemotePickerState> {
    val rootUrl = route.args.rootUrl
    val credentials = WebDavCredentials.of(
        username = route.args.username,
        password = route.args.password,
    )
    return remotePickerStateFlow(
        mode = route.args.mode,
        initialPath = normalizeWebDavRelativePath(route.args.initialPath),
        initialFileName = route.args.initialFileName,
        // Some servers match names case-insensitively.
        ignoreNameCase = true,
        load = { path ->
            listWebDavDirectory(
                ListWebDavDirectory.Request(
                    rootUrl = rootUrl,
                    path = path,
                    credentials = credentials,
                ),
            ).attempt().bind()
                .map { children -> children.map(::webDavPickerEntry) }
        },
        folderResult = { path ->
            resolveWebDavResourceUrl(
                baseUrl = rootUrl,
                path = path,
                collection = true,
            )
        },
        fileResult = { path ->
            resolveWebDavResourceUrl(
                baseUrl = rootUrl,
                path = path,
            )
        },
        onComplete = onComplete,
    )
}

private fun webDavPickerEntry(
    child: ListWebDavDirectory.Child,
) = RemotePickerEntry(
    path = child.path,
    name = child.name,
    isFolder = child.isCollection,
    size = child.size,
)
