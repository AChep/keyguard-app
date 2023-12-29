package com.artemchep.keyguard.common.usecase.impl

import android.app.Application
import android.content.Context
import com.artemchep.keyguard.android.downloader.DownloadManagerImpl
import com.artemchep.keyguard.android.downloader.journal.DownloadRepository
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.CleanUpAttachment
import com.artemchep.keyguard.common.usecase.GetCiphers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

/**
 * @author Artem Chepurnyi
 */
class CleanUpDownloadImpl(
    private val downloadRepository: DownloadRepository,
    private val getCiphers: GetCiphers,
) {
    constructor(directDI: DirectDI) : this(
        downloadRepository = directDI.instance(),
        getCiphers = directDI.instance(),
    )

    fun invoke(): IO<Unit> = ioEffect {
        val urls = getCiphers()
            .first()
            .asSequence()
            .flatMap {
                it.attachments
                    .asSequence()
                    .map { it.url }
            }
            .toSet()
        val kkk = downloadRepository.get()
            .map { downloadInfoList ->
                downloadInfoList
                    .asSequence()
                    .map { it.url }
                    .toSet()
            }
            .first()
        kkk.subtract(urls).forEach { url ->
            // TODO
            // downloadRepository.removeByUrl(url)
        }
    }
}

/**
 * @author Artem Chepurnyi
 */
class CleanUpAttachmentImpl(
    private val context: Context,
    private val logRepository: LogRepository,
    private val downloadRepository: DownloadRepository,
) : CleanUpAttachment {
    companion object {
        private const val TAG = "CleanUpAttachment"

        fun zzz(
            scope: CoroutineScope,
            downloadRepository: DownloadRepository,
            cleanUpAttachment: CleanUpAttachment,
        ): Job = scope.launch {
            // We want to avoid launching a ton of jobs
            // immediately on the start.
            val delay = with(Duration) { 30L.seconds }
            delay(delay)

            // Each time file journal changes we check for the unused files.
            downloadRepository.get()
                .debounce(1000L)
                .onEach {
                    cleanUpAttachment()
                        .attempt()
                        .bind()
                }
                .launchIn(scope)

            // Each time ciphers change we check for the removed urls.
//            getCiphers()
//                .debounce(1000L)
//                .onEach {
//                    cleanUpDownload.invoke()
//                        .attempt()
//                        .bind()
//                }
//                .launchIn(scope)
        }
    }

    constructor(directDI: DirectDI) : this(
        context = directDI.instance<Application>(),
        logRepository = directDI.instance(),
        downloadRepository = directDI.instance(),
    )

    override fun invoke(): IO<Int> = ioEffect(Dispatchers.IO) {
        val dir = DownloadManagerImpl.getDir(context)

        val actualFiles = dir
            .listFiles()
            .orEmpty()
        val possibleFiles = kotlin.run {
            val journal = downloadRepository.get().first()
            journal
                .asSequence()
                .map { downloadInfo ->
                    val fileId = downloadInfo.id
                    DownloadManagerImpl.getFile(
                        dir = dir,
                        downloadId = fileId,
                    )
                }
                .toSet()
        }

        val filesToDelete = actualFiles
            .filter { it !in possibleFiles }
        // Delete files
        filesToDelete.forEach { file ->
            file.delete()
        }
        filesToDelete.size
    }.measure { duration, deletedFiles ->
        val message = "Deleted $deletedFiles files in $duration"
        logRepository.post(TAG, message, LogLevel.INFO)
    }
}
