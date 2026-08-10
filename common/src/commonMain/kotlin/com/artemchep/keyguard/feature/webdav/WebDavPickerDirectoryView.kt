package com.artemchep.keyguard.feature.webdav

import arrow.core.Either
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.WebDavCredentials
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.model.map
import com.artemchep.keyguard.common.usecase.ListWebDavDirectory
import com.artemchep.keyguard.util.webdav.resolveWebDavResourceUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class WebDavPickerDirectoryView(
    val path: String,
    val breadcrumbs: List<WebDavPickerState.Breadcrumb>,
    val content: Loadable<Either<Throwable, List<WebDavPickerState.Item>>>,
    val existingNames: List<String>,
)

internal fun webDavPickerDirectoryFlow(
    route: WebDavPickerRoute,
    pathFlow: Flow<String>,
    refreshFlow: Flow<Int>,
    listWebDavDirectory: ListWebDavDirectory,
    onPathChange: (String) -> Unit,
    onComplete: (String) -> Unit,
): Flow<WebDavPickerDirectoryView> {
    val rootUrl = route.args.rootUrl
    val mode = route.args.mode
    val credentials = WebDavCredentials.of(
        username = route.args.username,
        password = route.args.password,
    )
    return webDavPickerDirectoryLoadFlow(
        pathFlow = pathFlow,
        refreshFlow = refreshFlow,
    ) { path ->
        listWebDavDirectory(
            ListWebDavDirectory.Request(
                rootUrl = rootUrl,
                path = path,
                credentials = credentials,
            ),
        ).attempt().bind()
    }.map { (path, load) ->
        // Derive everything that depends on the directory content once
        // per load, so that typing a file name does not redo this work.
        val content = load.map { result ->
            result.map { children ->
                sortWebDavDirectoryChildren(children)
                    .map { child ->
                        webDavPickerItem(
                            child = child,
                            mode = mode,
                            rootUrl = rootUrl,
                            onPathChange = onPathChange,
                            onComplete = onComplete,
                        )
                    }
            }
        }
        WebDavPickerDirectoryView(
            path = path,
            breadcrumbs = webDavPickerBreadcrumbs(path, onPathChange),
            content = content,
            existingNames = webDavPickerExistingResourceNames(
                content.getOrNull()?.getOrNull().orEmpty(),
            ),
        )
    }
}

private fun webDavPickerItem(
    child: ListWebDavDirectory.Child,
    mode: WebDavPickerRoute.Mode,
    rootUrl: String,
    onPathChange: (String) -> Unit,
    onComplete: (String) -> Unit,
) = WebDavPickerState.Item(
    key = child.path,
    name = child.name,
    isCollection = child.isCollection,
    size = child.size,
    onClick = when {
        child.isCollection -> {
            {
                onPathChange(child.path)
            }
        }

        isWebDavPickerFileSelectable(
            mode = mode,
            fileName = child.name,
        ) -> {
            {
                onComplete(
                    resolveWebDavResourceUrl(
                        baseUrl = rootUrl,
                        path = child.path,
                    ),
                )
            }
        }

        else -> null
    },
)

internal fun webDavPickerOnConfirm(
    view: WebDavPickerDirectoryView,
    mode: WebDavPickerRoute.Mode,
    rootUrl: String,
    fileName: String,
    fileNameError: WebDavPickerState.FileNameError?,
    onComplete: (String) -> Unit,
): (() -> Unit)? {
    val loaded = view.content.getOrNull()?.isRight() == true
    return when (mode) {
        WebDavPickerRoute.Mode.SelectCollection -> if (loaded) {
            {
                onComplete(
                    resolveWebDavResourceUrl(
                        baseUrl = rootUrl,
                        path = view.path,
                        collection = true,
                    ),
                )
            }
        } else {
            null
        }

        WebDavPickerRoute.Mode.OpenKeePassDatabase -> null

        WebDavPickerRoute.Mode.CreateKeePassDatabase -> if (loaded && fileNameError == null) {
            {
                onComplete(
                    resolveWebDavResourceUrl(
                        baseUrl = rootUrl,
                        path = joinWebDavPickerPath(
                            view.path,
                            fileName.trim(),
                        ),
                    ),
                )
            }
        } else {
            null
        }
    }
}
