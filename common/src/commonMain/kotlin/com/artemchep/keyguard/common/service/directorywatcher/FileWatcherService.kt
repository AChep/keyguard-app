package com.artemchep.keyguard.common.service.directorywatcher

import com.artemchep.keyguard.common.service.file.toLocalPathFromFileUriOrNull
import com.artemchep.keyguard.platform.LocalPath
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
}
