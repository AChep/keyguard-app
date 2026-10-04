package com.artemchep.keyguard.feature.remotepicker

import arrow.core.Either
import com.artemchep.keyguard.common.model.Loadable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest

internal fun remotePickerExistingNames(
    items: List<RemotePickerState.Item>,
): List<String> = items.map { item -> item.name }

internal fun <T> remotePickerDirectoryLoadFlow(
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

internal fun joinRemotePickerPath(
    parent: String,
    name: String,
): String = if (parent.isEmpty()) name else "$parent/$name"

/** Builds the breadcrumbs of a `/`-separated [path] relative to the picker root. */
internal fun remotePickerBreadcrumbs(
    path: String,
    onClick: (String) -> Unit,
): List<RemotePickerState.Breadcrumb> {
    val parts = path
        .split('/')
        .filter { part -> part.isNotEmpty() }
    val paths = parts.runningFold("") { parent, part ->
        joinRemotePickerPath(parent, part)
    }
    return paths.mapIndexed { index, itemPath ->
        RemotePickerState.Breadcrumb(
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

/** [ignoreCase] tells whether the storage treats names that differ only in case as the same file. */
internal fun validateRemotePickerFileName(
    fileName: String,
    existingNames: List<String>,
    ignoreCase: Boolean = true,
): RemotePickerState.FileNameError? {
    val normalized = fileName.trim()
    return when {
        normalized.isEmpty() -> RemotePickerState.FileNameError.Required
        normalized == "." ||
                normalized == ".." ||
                '/' in normalized ||
                '\\' in normalized ->
            RemotePickerState.FileNameError.Invalid

        !normalized.endsWith(KEEPASS_DATABASE_EXTENSION, ignoreCase = true) ->
            RemotePickerState.FileNameError.ExtensionRequired

        existingNames.any { name -> name.equals(normalized, ignoreCase = ignoreCase) } ->
            RemotePickerState.FileNameError.AlreadyExists

        else -> null
    }
}

internal fun isRemotePickerFileSelectable(
    mode: RemotePickerMode,
    fileName: String,
): Boolean =
    mode == RemotePickerMode.OpenKeePassDatabase &&
            fileName.endsWith(KEEPASS_DATABASE_EXTENSION, ignoreCase = true)

internal const val DEFAULT_REMOTE_PICKER_DATABASE_NAME = "database.kdbx"
internal const val KEEPASS_DATABASE_EXTENSION = ".kdbx"
