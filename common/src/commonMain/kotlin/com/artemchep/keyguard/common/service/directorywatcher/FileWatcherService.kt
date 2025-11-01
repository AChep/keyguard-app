package com.artemchep.keyguard.common.service.directorywatcher

import kotlinx.coroutines.flow.Flow
import java.io.File

interface FileWatcherService {
    fun fileChangedFlow(
        file: File,
    ): Flow<FileWatchEvent>
}
