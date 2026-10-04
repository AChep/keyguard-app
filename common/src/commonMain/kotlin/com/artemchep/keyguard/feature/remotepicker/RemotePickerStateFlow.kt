package com.artemchep.keyguard.feature.remotepicker

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import arrow.core.Either
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.model.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private class RemotePickerDirectoryView(
    val path: String,
    val breadcrumbs: List<RemotePickerState.Breadcrumb>,
    val content: Loadable<Either<Throwable, List<RemotePickerState.Item>>>,
    val existingNames: List<String>,
)

/**
 * Browses a remote storage. [folderResult] and [fileResult] turn a picker
 * path into the value that the picker completes with.
 */
internal fun remotePickerStateFlow(
    mode: RemotePickerMode,
    initialPath: String,
    initialFileName: String,
    ignoreNameCase: Boolean,
    load: suspend (path: String) -> Either<Throwable, List<RemotePickerEntry>>,
    folderResult: (path: String) -> String,
    fileResult: (path: String) -> String,
    onComplete: (String) -> Unit,
): Flow<RemotePickerState> {
    val pathSink = MutableStateFlow(initialPath)
    val refreshSink = MutableStateFlow(0)
    val fileNameState = if (mode == RemotePickerMode.CreateKeePassDatabase) {
        mutableStateOf(
            initialFileName
                .ifBlank { DEFAULT_REMOTE_PICKER_DATABASE_NAME },
        )
    } else {
        null
    }
    val onFile: (String) -> Unit = { path ->
        onComplete(fileResult(path))
    }
    val directoryFlow = remotePickerDirectoryFlow(
        mode = mode,
        pathSink = pathSink,
        refreshFlow = refreshSink,
        load = load,
        onFile = onFile,
    )

    val fileNameFlow = fileNameState
        ?.let { state -> snapshotFlow { state.value } }
        ?: MutableStateFlow("")

    return combine(
        directoryFlow,
        fileNameFlow,
    ) { view, fileName ->
        val fileNameError = if (mode == RemotePickerMode.CreateKeePassDatabase) {
            validateRemotePickerFileName(
                fileName = fileName,
                existingNames = view.existingNames,
                ignoreCase = ignoreNameCase,
            )
        } else {
            null
        }
        RemotePickerState(
            path = view.path,
            breadcrumbs = view.breadcrumbs,
            content = view.content,
            fileName = fileNameState,
            fileNameError = fileNameError,
            onConfirm = remotePickerOnConfirm(
                path = view.path,
                loaded = view.content.getOrNull()?.isRight() == true,
                mode = mode,
                fileName = fileName,
                fileNameError = fileNameError,
                onFolder = { path -> onComplete(folderResult(path)) },
                onFile = onFile,
            ),
            onRefresh = { refreshSink.value += 1 },
        )
    }
}

private fun remotePickerDirectoryFlow(
    mode: RemotePickerMode,
    pathSink: MutableStateFlow<String>,
    refreshFlow: Flow<Int>,
    load: suspend (path: String) -> Either<Throwable, List<RemotePickerEntry>>,
    onFile: (String) -> Unit,
): Flow<RemotePickerDirectoryView> {
    val onPathChange: (String) -> Unit = { path ->
        pathSink.value = path
    }
    return remotePickerDirectoryLoadFlow(
        pathFlow = pathSink,
        refreshFlow = refreshFlow,
        load = load,
    ).map { (path, load) ->
        // Derive everything that depends on the directory content once
        // per load, so that typing a file name does not redo this work.
        val content = load.map { result ->
            result.map { entries ->
                sortRemotePickerEntries(entries)
                    .map { entry ->
                        remotePickerItem(
                            entry = entry,
                            mode = mode,
                            onPathChange = onPathChange,
                            onFile = onFile,
                        )
                    }
            }
        }
        RemotePickerDirectoryView(
            path = path,
            breadcrumbs = remotePickerBreadcrumbs(path, onPathChange),
            content = content,
            existingNames = remotePickerExistingNames(
                content.getOrNull()?.getOrNull().orEmpty(),
            ),
        )
    }
}

private fun remotePickerItem(
    entry: RemotePickerEntry,
    mode: RemotePickerMode,
    onPathChange: (String) -> Unit,
    onFile: (String) -> Unit,
) = RemotePickerState.Item(
    // A folder and a file may share a name.
    key = if (entry.isFolder) "${entry.path}/" else entry.path,
    name = entry.name,
    isFolder = entry.isFolder,
    size = entry.size,
    onClick = when {
        entry.isFolder -> {
            {
                onPathChange(entry.path)
            }
        }

        entry.isAddressable && isRemotePickerFileSelectable(mode, entry.name) -> {
            {
                onFile(entry.path)
            }
        }

        else -> null
    },
)

/** Returns the confirm action: [onFolder] gets the current path, [onFile] the path of the new file. */
internal fun remotePickerOnConfirm(
    path: String,
    loaded: Boolean,
    mode: RemotePickerMode,
    fileName: String,
    fileNameError: RemotePickerState.FileNameError?,
    onFolder: (String) -> Unit,
    onFile: (String) -> Unit,
): (() -> Unit)? = when (mode) {
    RemotePickerMode.SelectFolder -> if (loaded) {
        {
            onFolder(path)
        }
    } else {
        null
    }

    RemotePickerMode.OpenKeePassDatabase -> null

    RemotePickerMode.CreateKeePassDatabase -> if (loaded && fileNameError == null) {
        {
            onFile(joinRemotePickerPath(path, fileName.trim()))
        }
    } else {
        null
    }
}

internal fun sortRemotePickerEntries(
    entries: List<RemotePickerEntry>,
): List<RemotePickerEntry> = entries.sortedWith(
    compareByDescending<RemotePickerEntry> { entry ->
        entry.isFolder
    }.thenComparator { a, b ->
        a.name.compareTo(b.name, ignoreCase = true)
    }.thenBy { entry ->
        entry.name
    },
)
