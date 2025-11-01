package com.artemchep.keyguard.copy.download

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoEntity2
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.core.use
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.File
import java.io.IOException

abstract class DownloadClientJvm(
    private val cacheDirProvider: CacheDirProvider,
    private val cryptoGenerator: CryptoGenerator,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val okHttpClient: OkHttpClient,
    private val fileEncryptor: FileEncryptor,
) {
    companion object {
        private const val DOWNLOAD_PROGRESS_POOLING_PERIOD_MS = 1000L
    }

    private data class PoolEntry(
        val subscribersCount: Int,
        val downloadId: String,
        val url: String,
        val tag: DownloadInfoEntity2.AttachmentDownloadTag,
        val file: File,
        val fileKey: ByteArray?,
        val flow: Flow<DownloadProgress>,
    )

    private val sink =
        MutableStateFlow(persistentMapOf<DownloadInfoEntity2.AttachmentDownloadTag, PoolEntry>())

    private val flowOfNone = flowOf(DownloadProgress.None)

    constructor(
        directDI: DirectDI,
    ) : this(
        cacheDirProvider = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
        okHttpClient = directDI.instance(),
        fileEncryptor = directDI.instance(),
    )

    private fun fileStatusBy(predicate: (PoolEntry) -> Boolean) = sink
        .map { state ->
            val entryOrNull = state.values.firstOrNull(predicate)
            entryOrNull?.flow
                ?: flowOfNone
        }
        .distinctUntilChanged()
        .flatMapLatest { it }

    fun fileStatusByDownloadId(downloadId: String) = fileStatusBy { it.downloadId == downloadId }

    fun fileStatusByTag(tag: DownloadInfoEntity2.AttachmentDownloadTag) =
        fileStatusBy { it.tag == tag }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun fileLoader(
        downloadId: String,
        url: String,
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
        file: File,
        fileData: ByteArray? = null,
        fileKey: ByteArray? = null,
        cancelFlow: Flow<Unit>,
    ): Flow<DownloadProgress> = flow {
        try {
            synchronized(sink) {
                fun createEntry() = kotlin.run {
                    val sharedDownloadScope = windowCoroutineScope + SupervisorJob()
                    val sharedDownloadFlow = flow {
                        val internalFlow = internalFileLoader(
                            url = url,
                            file = file,
                            fileData = fileData,
                            fileKey = fileKey,
                        )

                        coroutineScope {
                            // Listen to the cancellation flow and cancel the
                            // scope on any event.
                            cancelFlow
                                .onEach { sharedDownloadScope.cancel() }
                                .launchIn(this)

                            emitAll(internalFlow)
                        }
                    }.shareIn(sharedDownloadScope, SharingStarted.Eagerly, replay = 1)
                    val cancellableDownloadFlow = channelFlow<DownloadProgress> {
                        val job = sharedDownloadScope.launch {
                            try {
                                sharedDownloadFlow
                                    .onEach { status -> send(status) }
                                    .collect()
                            } finally {
                                // The scope is dead, but the flow is still alive, therefore
                                // someone has canceled the scope.
                                if (!this@channelFlow.isClosedForSend) {
                                    val event = DownloadProgress.Complete(
                                        result = RuntimeException("Canceled").left(),
                                    )
                                    trySend(event)
                                }
                            }
                        }

                        job.join()
                    }
                        .transformWhile { progress ->
                            emit(progress) // always emit progress
                            progress is DownloadProgress.Loading
                        }
                    PoolEntry(
                        subscribersCount = 0,
                        downloadId = downloadId,
                        url = url,
                        tag = tag,
                        file = file,
                        fileKey = fileKey,
                        flow = cancellableDownloadFlow,
                    )
                }

                val pool = sink.value
                var value = pool.getOrElse(tag) {
                    createEntry()
                }
                if (!value.fileKey.contentEquals(fileKey)) {
                    // TODO: Cancel previous flow and clear existing files if
                    //  needed!
                    // This should never happen, the file key has changed while
                    // we were loading the file.
                    value = createEntry()
                }
                // Increment the number of subscribers.
                val newSubscribersCount = value.subscribersCount + 1
                val newValue = value.copy(subscribersCount = newSubscribersCount)
                val newPool = pool.put(tag, newValue)
                sink.value = newPool
                // return the flow
                newValue.flow
            }
                .collect(this)
        } finally {
            synchronized(sink) {
                val pool = sink.value
                val value = pool.getValue(tag)
                val subscribersCount = value.subscribersCount - 1
                require(subscribersCount >= 0) {
                    "Active subscribers count of '$downloadId' is less than zero!"
                }

                val newPool = when (subscribersCount) {
                    0 -> pool.remove(tag)
                    else -> {
                        val newSubscribersCount = value.subscribersCount - 1
                        pool.put(tag, value.copy(subscribersCount = newSubscribersCount))
                    }
                }
                sink.value = newPool
            }
        }
    }

    private fun internalFileLoader(
        url: String,
        file: File,
        fileData: ByteArray? = null,
        fileKey: ByteArray? = null,
    ): Flow<DownloadProgress> = flow {
        val exists = file.exists()
        if (exists) {
            // No need to download anything, the file is
            // already available locally.
            val f = flow<DownloadProgress> {
                val result = file.right()
                emit(DownloadProgress.Complete(result))
            }
            emitAll(f)
            return@flow
        }
        file.parentFile?.mkdirs()

        val f = channelFlow<DownloadProgress> {
            val cacheFile = kotlin.runCatching {
                val cacheFileName = cryptoGenerator.uuid() + ".download"
                val cacheFileRelativePath = "download_cache/$cacheFileName"
                cacheDirProvider.get().resolve(cacheFileRelativePath)
            }.getOrElse { e ->
                // Report the download as failed if we could not
                // resolve a cache file.
                val event = DownloadProgress.Complete(
                    result = e.left(),
                )
                send(event)
                return@channelFlow
            }

            val result = try {
                if (fileData != null) {
                    cacheFile.delete()
                    cacheFile.parentFile?.mkdirs()
                    cacheFile.writeBytes(fileData)

                    val result = cacheFile
                        .right()
                    DownloadProgress.Complete(
                        result = result,
                    )
                } else {
                    flap(
                        src = url,
                        dst = cacheFile,
                    )
                }
            } catch (e: Exception) {
                // Delete cache file in case of
                // an error.
                runCatching {
                    cacheFile.delete()
                }

                e.throwIfFatalOrCancellation()

                val result = e.left()
                DownloadProgress.Complete(
                    result = result,
                )
            }
            send(result)
        }
            .flatMapConcat { event ->
                println("events $event")
                when (event) {
                    is DownloadProgress.Complete ->
                        event.result
                            .fold(
                                ifLeft = {
                                    flowOf(event)
                                },
                                ifRight = { tmpFile ->
                                    // Decrypt the file and move it to the final
                                    // destination.
                                    flow {
                                        emit(DownloadProgress.Loading())
                                        val result = kotlin
                                            .runCatching {
                                                tmpFile.decryptAndMove(
                                                    dst = file,
                                                    key = fileKey,
                                                )
                                            }
                                            .fold(
                                                onFailure = { e ->
                                                    e.printStackTrace()
                                                    e.left()
                                                },
                                                onSuccess = {
                                                    file.right()
                                                },
                                            )
                                        emit(DownloadProgress.Complete(result))
                                    }
                                },
                            )

                    is DownloadProgress.Loading -> flowOf(event)
                    is DownloadProgress.None -> flowOf(event)
                }
            }
        emitAll(f)
    }
        .onStart {
            val initialState = DownloadProgress.Loading()
            emit(initialState)
        }

    // TODO: What if SRC file is DST file??
    private suspend fun File.decryptAndMove(
        dst: File,
        key: ByteArray? = null,
    ) = withContext(Dispatchers.IO) {
        // If there's nothing to decrypt and the file
        // matches destination then we are done here.
        val sameFile = this@decryptAndMove == dst
        if (sameFile && key == null) {
            return@withContext
        }

        dst.parentFile?.mkdirs()

        if (key != null) {
            dst.delete()
            inputStream()
                .use { fis ->
                    val ctis = fileEncryptor.decode(
                        input = fis,
                        key = key,
                    )
                    ctis.use { i ->
                        dst.outputStream().use { o ->
                            i.copyTo(o)
                        }
                    }
                }
        } else {
            copyTo(dst, overwrite = true)
        }

        if (!sameFile) this@decryptAndMove.delete()
    }

    private suspend fun ProducerScope<DownloadProgress>.flap(
        src: String,
        dst: File,
    ): DownloadProgress.Complete {
        val response = kotlin.run {
            val request = Request.Builder()
                .get()
                .url(src)
                .build()
            okHttpClient.newCall(request).execute()
        }
        if (!response.isSuccessful) {
            val exception = HttpException(
                statusCode = HttpStatusCode.fromValue(response.code),
                m = response.message,
                e = null,
            )
            val result = exception.left()
            return DownloadProgress.Complete(
                result = result,
            )
        }
        val responseBody = response.body
            ?: throw IOException("File is not available!")

        //
        // Check if the file is already loaded
        //

        val dstContentLength = dst.length()
        val srcContentLength = responseBody.contentLength()
        if (dstContentLength == srcContentLength) {
            val result = dst.right()
            return DownloadProgress.Complete(
                result = result,
            )
        }

        dst.delete()
        dst.parentFile?.mkdirs()

        coroutineScope {
            var totalBytesWritten = 0L

            val monitorJob = launch {
                delay(DOWNLOAD_PROGRESS_POOLING_PERIOD_MS / 2)
                while (isActive) {
                    val event = DownloadProgress.Loading(
                        downloaded = totalBytesWritten,
                        total = srcContentLength,
                    )
                    trySend(event)

                    // Wait a bit before the next status update.
                    delay(DOWNLOAD_PROGRESS_POOLING_PERIOD_MS)
                }
            }

            withContext(Dispatchers.IO) {
                responseBody.byteStream().use { inputStream ->
                    dst.outputStream().use { outputStream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val bytes = inputStream.read(buffer)
                            if (bytes != -1) {
                                outputStream.write(buffer, 0, bytes)
                                totalBytesWritten += bytes
                            } else {
                                break
                            }
                        }
                    }
                }
            }
            monitorJob.cancelAndJoin()
        }

        val result = dst.right()
        return DownloadProgress.Complete(
            result = result,
        )
    }
}
