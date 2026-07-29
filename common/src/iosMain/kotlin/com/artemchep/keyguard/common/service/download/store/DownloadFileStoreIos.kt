package com.artemchep.keyguard.common.service.download.store

import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.platform.iosKeyguardAtomicDataDirectory
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath

object DownloadFileStoreIos : DownloadFileStoreLocalPath() {
    override suspend fun destination(
        info: DownloadInfoEntity,
    ): AtomicFileDestination = iosKeyguardAtomicDataDirectory()
        .resolveDirectory(AtomicPathComponent.parse("downloads"))
        .resolve(AtomicPathComponent.parse("${info.id}.bin"))
}
