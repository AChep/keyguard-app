package com.artemchep.keyguard.common.usecase.impl

import android.app.Application
import android.content.Context
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.service.download.DownloadRepository
import com.artemchep.keyguard.common.service.download.store.DownloadFileStoreAndroid
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.CleanUpAttachment
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.feature.filepicker.AndroidFileDropStorage
import com.artemchep.keyguard.util.io.artifact.isReservedTemporaryArtifactName
import com.artemchep.keyguard.util.io.artifact.sweepTemporaryArtifacts
import com.artemchep.keyguard.util.io.toLocalPath
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
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

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
        private val PRIVATE_TEMPORARY_FILE_GRACE_PERIOD = 24.hours

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
        val dir = DownloadFileStoreAndroid.getDir(context)
        // Reserved native artifacts are protected by their declared lease
        // protocol. Never infer that one is safe to unlink from its age here.
        val nativeSweep = sweepTemporaryArtifacts(
            directory = dir.toLocalPath(),
            olderThan = PRIVATE_TEMPORARY_FILE_GRACE_PERIOD,
        )

        val actualFiles = dir
            .listFiles()
            .orEmpty()
        val possibleFiles = kotlin.run {
            val journal = downloadRepository.get().first()
            journal
                .asSequence()
                .map { downloadInfo ->
                    val fileId = downloadInfo.id
                    DownloadFileStoreAndroid.getFile(
                        dir = dir,
                        downloadId = fileId,
                    )
                }
                .toSet()
        }

        val filesToDelete = actualFiles
            .filter { file ->
                shouldDeleteAttachmentFile(
                    file = file,
                    possibleFiles = possibleFiles,
                )
            }
        // Delete files
        val deletedFiles = filesToDelete.count { file ->
            file.delete()
        }
        val droppedFilesToDelete = AndroidFileDropStorage.cleanUpStale(context)
        nativeSweep.removed
            .coerceAtMost(Int.MAX_VALUE.toULong())
            .toInt() + deletedFiles + droppedFilesToDelete
    }.measure { duration, deletedFiles ->
        val message = "Deleted $deletedFiles files in $duration"
        logRepository.post(TAG, message, LogLevel.INFO)
    }
}

internal fun shouldDeleteAttachmentFile(
    file: File,
    possibleFiles: Set<File>,
): Boolean = when {
    file in possibleFiles -> false
    // Malformed and future-version names are reserved too. The native sweeper
    // alone may inspect and remove them under the matching lease protocol.
    isReservedTemporaryArtifactName(file.name) -> false
    else -> true
}
