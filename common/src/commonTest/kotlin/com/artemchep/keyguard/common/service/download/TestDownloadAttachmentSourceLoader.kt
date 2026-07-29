package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import kotlinx.coroutines.flow.Flow

/**
 * Adapts a [DownloadTask] fake to the [DownloadAttachmentSourceLoader]
 * interface for tests that only exercise direct and URL sources.
 */
internal fun DownloadTask.asSourceLoader(): DownloadAttachmentSourceLoader =
    object : DownloadAttachmentSourceLoader {
        override fun fileLoader(
            request: DownloadAttachmentRequestData,
            writer: DownloadWriter,
        ): Flow<DownloadProgress> = when (val source = request.source) {
            is DownloadAttachmentRequestData.DirectSource -> fileLoader(
                data = source.data,
                key = request.encryptionKey,
                writer = writer,
            )

            is DownloadAttachmentRequestData.UrlSource -> fileLoader(
                url = source.url,
                key = request.encryptionKey,
                writer = writer,
            )

            is DownloadAttachmentRequestData.KeePassSource ->
                error("Unexpected KeePass source in DownloadTask-backed test loader.")
        }
    }
