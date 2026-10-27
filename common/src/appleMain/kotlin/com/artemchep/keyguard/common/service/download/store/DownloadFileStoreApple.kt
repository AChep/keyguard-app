package com.artemchep.keyguard.common.service.download.store

import com.artemchep.keyguard.platform.AppleStorageDirectories
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent

class DownloadFileStoreApple(directories: AppleStorageDirectories) : NamedDownloadFileStore(
    directory = {
        getDir(directories)
    },
) {
    companion object {
        fun getDir(directories: AppleStorageDirectories) = directories.data
            .resolveDirectory(AtomicPathComponent.parse("downloads"))
    }
}
