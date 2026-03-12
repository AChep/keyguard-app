package com.artemchep.keyguard.common.service.directorywatcher

import com.artemchep.keyguard.platform.LocalPath
import kotlinx.coroutines.flow.Flow

interface FileWatcherService {
    fun fileChangedFlow(
        file: LocalPath,
    ): Flow<FileWatchEvent>
}
