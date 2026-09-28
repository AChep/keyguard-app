package com.artemchep.keyguard.common.service.download.store

import com.artemchep.keyguard.platform.appleKeyguardAtomicDataDirectory
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent

object DownloadFileStoreApple : NamedDownloadFileStore(
    directory = {
        appleKeyguardAtomicDataDirectory()
            .resolveDirectory(AtomicPathComponent.parse("downloads"))
    },
)
