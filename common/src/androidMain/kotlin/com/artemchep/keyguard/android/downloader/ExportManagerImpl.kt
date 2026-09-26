package com.artemchep.keyguard.android.downloader

import android.app.Application
import android.content.Context
import com.artemchep.keyguard.android.downloader.worker.ExportWorker
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.download.DownloadAttachmentSourceLoader
import com.artemchep.keyguard.common.service.export.ExportVaultDataService
import com.artemchep.keyguard.common.service.export.impl.ExportManagerBase
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.util.zip.ZipService

class ExportManagerImpl(

    windowCoroutineScope: WindowCoroutineScope,
    cryptoGenerator: CryptoGenerator,
    exportVaultDataService: ExportVaultDataService,
    dirsService: DirsService,
    zipService: ZipService,
    dateFormatter: DateFormatter,
    downloadSourceLoader: DownloadAttachmentSourceLoader,
    downloadAttachmentMetadata: DownloadAttachmentMetadata,
    vaultSessionLocker: VaultSessionLocker,
    private val context: Context,
) : ExportManagerBase(
    windowCoroutineScope = windowCoroutineScope,
    cryptoGenerator = cryptoGenerator,
    exportVaultDataService = exportVaultDataService,
    dirsService = dirsService,
    zipService = zipService,
    dateFormatter = dateFormatter,
    downloadSourceLoader = downloadSourceLoader,
    downloadAttachmentMetadata = downloadAttachmentMetadata,
    vaultSessionLocker = vaultSessionLocker,
    onLaunch = { id ->
        val args = ExportWorker.Args(
            exportId = id,
        )
        ExportWorker.enqueueOnce(
            context = context,
            args = args,
        )
    }
)
