package com.artemchep.keyguard.feature.webdav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import arrow.core.Either
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.ListWebDavDirectory
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.util.webdav.normalizeWebDavRelativePath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceWebDavPickerState(
    route: WebDavPickerRoute,
    transmitter: RouteResultTransmitter<WebDavPickerResult>,
): WebDavPickerState = with(localDI().direct) {
    produceWebDavPickerState(
        route = route,
        transmitter = transmitter,
        listWebDavDirectory = instance(),
    )
}

@Composable
fun produceWebDavPickerState(
    route: WebDavPickerRoute,
    transmitter: RouteResultTransmitter<WebDavPickerResult>,
    listWebDavDirectory: ListWebDavDirectory,
): WebDavPickerState = produceScreenState(
    key = "webdav_picker",
    initial = WebDavPickerState(
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
): Flow<WebDavPickerState> {
    val mode = route.args.mode
    val pathSink = MutableStateFlow(normalizeWebDavRelativePath(route.args.initialPath))
    val refreshSink = MutableStateFlow(0)
    val fileNameState = if (mode == WebDavPickerRoute.Mode.CreateKeePassDatabase) {
        mutableStateOf(
            route.args.initialFileName
                .ifBlank { DEFAULT_WEBDAV_DATABASE_NAME },
        )
    } else {
        null
    }
    val onPathChange: (String) -> Unit = { path ->
        pathSink.value = path
    }
    val directoryFlow = webDavPickerDirectoryFlow(
        route = route,
        pathFlow = pathSink,
        refreshFlow = refreshSink,
        listWebDavDirectory = listWebDavDirectory,
        onPathChange = onPathChange,
        onComplete = onComplete,
    )

    val fileNameFlow = fileNameState
        ?.let { state -> snapshotFlow { state.value } }
        ?: MutableStateFlow("")

    return combine(
        directoryFlow,
        fileNameFlow,
    ) { view, fileName ->
        val fileNameError = if (mode == WebDavPickerRoute.Mode.CreateKeePassDatabase) {
            validateWebDavPickerFileName(
                fileName = fileName,
                existingNames = view.existingNames,
            )
        } else {
            null
        }
        WebDavPickerState(
            path = view.path,
            breadcrumbs = view.breadcrumbs,
            content = view.content,
            fileName = fileNameState,
            fileNameError = fileNameError,
            onConfirm = webDavPickerOnConfirm(
                view = view,
                mode = mode,
                rootUrl = route.args.rootUrl,
                fileName = fileName,
                fileNameError = fileNameError,
                onComplete = onComplete,
            ),
            onRefresh = { refreshSink.value += 1 },
        )
    }
}

internal fun webDavPickerExistingResourceNames(
    items: List<WebDavPickerState.Item>,
): List<String> = items.map { item -> item.name }

internal fun <T> webDavPickerDirectoryLoadFlow(
    pathFlow: Flow<String>,
    refreshFlow: Flow<Int>,
    load: suspend (String) -> Either<Throwable, T>,
): Flow<Pair<String, Loadable<Either<Throwable, T>>>> = combine(
    pathFlow,
    refreshFlow,
) { path, _ -> path }
    .transformLatest { path ->
        emit(path to Loadable.Loading)
        emit(path to Loadable.Ok(load(path)))
    }

internal fun joinWebDavPickerPath(
    parent: String,
    name: String,
): String = if (parent.isEmpty()) name else "$parent/$name"

internal fun webDavPickerBreadcrumbs(
    path: String,
    onClick: (String) -> Unit,
): List<WebDavPickerState.Breadcrumb> {
    val parts = normalizeWebDavRelativePath(path)
        .split('/')
        .filter { part -> part.isNotEmpty() }
    val paths = parts.runningFold("") { parent, part ->
        joinWebDavPickerPath(parent, part)
    }
    return paths.mapIndexed { index, itemPath ->
        WebDavPickerState.Breadcrumb(
            name = if (index == 0) "/" else parts[index - 1],
            onClick = if (index == paths.lastIndex) {
                null
            } else {
                {
                    onClick(itemPath)
                }
            },
        )
    }
}

internal fun validateWebDavPickerFileName(
    fileName: String,
    existingNames: List<String>,
): WebDavPickerState.FileNameError? {
    val normalized = fileName.trim()
    return when {
        normalized.isEmpty() -> WebDavPickerState.FileNameError.Required
        normalized == "." ||
                normalized == ".." ||
                '/' in normalized ||
                '\\' in normalized ->
            WebDavPickerState.FileNameError.Invalid

        !normalized.endsWith(WEBDAV_DATABASE_EXTENSION, ignoreCase = true) ->
            WebDavPickerState.FileNameError.ExtensionRequired

        existingNames.any { name -> name.equals(normalized, ignoreCase = true) } ->
            WebDavPickerState.FileNameError.AlreadyExists

        else -> null
    }
}

internal fun sortWebDavDirectoryChildren(
    children: List<ListWebDavDirectory.Child>,
): List<ListWebDavDirectory.Child> = children.sortedWith(
    compareByDescending<ListWebDavDirectory.Child> { child ->
        child.isCollection
    }.thenComparator { a, b ->
        a.name.compareTo(b.name, ignoreCase = true)
    }.thenBy { child ->
        child.name
    },
)

internal fun isWebDavPickerFileSelectable(
    mode: WebDavPickerRoute.Mode,
    fileName: String,
): Boolean =
    mode == WebDavPickerRoute.Mode.OpenKeePassDatabase &&
            fileName.endsWith(WEBDAV_DATABASE_EXTENSION, ignoreCase = true)

private const val DEFAULT_WEBDAV_DATABASE_NAME = "database.kdbx"
internal const val WEBDAV_DATABASE_EXTENSION = ".kdbx"
