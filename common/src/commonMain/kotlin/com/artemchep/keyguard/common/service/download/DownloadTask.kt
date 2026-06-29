package com.artemchep.keyguard.common.service.download

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

interface DownloadTask {
    fun fileLoader(
        data: ByteArray,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress>

    fun fileLoader(
        url: String,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress>
}

fun downloadDataToWriter(
    data: ByteArray,
    key: ByteArray?,
    writer: DownloadWriter,
    fileEncryptor: FileEncryptor,
): Flow<DownloadProgress> = flow<DownloadProgress> {
    val result = try {
        val plainBytes = key?.let { fileEncryptor.decode(data, it) } ?: data
        writer.writeBytes(plainBytes)
        writer.locationUri().right()
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
        e.left()
    }
    emit(DownloadProgress.Complete(result))
}.onStart {
    emit(DownloadProgress.Loading())
}
