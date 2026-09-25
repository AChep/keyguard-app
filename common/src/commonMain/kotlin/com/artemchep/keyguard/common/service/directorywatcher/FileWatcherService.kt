package com.artemchep.keyguard.common.service.directorywatcher

import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.util.io.toLocalPathFromFileUriOrNull
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface FileWatcherService {
    fun fileChangedFlow(
        file: LocalPath,
    ): Flow<FileWatchEvent>

    fun uriChangedFlow(
        uri: String,
    ): Flow<FileWatchEvent> = uri
        .toLocalPathFromFileUriOrNull()
        ?.let(::fileChangedFlow)
        // For unsupported URIs, suspend until
        // canceled instead of completing.
        ?: flow<FileWatchEvent> { awaitCancellation() }

    /** Watches a URI using its persisted access grant when the platform requires one. */
    fun uriChangedFlow(
        uri: String,
        accessToken: FileAccessToken?,
    ): Flow<FileWatchEvent> = uriChangedFlow(uri)
}
