package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.StreamingFileDecryptor
import com.artemchep.keyguard.platform.LocalPath
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.DELETE_ON_CLOSE
import java.nio.file.StandardOpenOption.READ

class FileEncryptorJvm(
    cryptoGenerator: CryptoGenerator,
) : NativeFileEncryptor(cryptoGenerator), StreamingFileDecryptor {
    constructor(
        directDI: DirectDI,
    ) : this(
        cryptoGenerator = directDI.instance(),
    )

    override fun decode(
        input: InputStream,
        key: ByteArray,
    ): InputStream {
        val stagedPlaintext = createPrivateTemporaryFile()
        try {
            input.asSource()
                .buffered()
                .use { source ->
                    super.decode(
                        input = source,
                        output = LocalPath(stagedPlaintext.absolutePath),
                        key = key,
                    )
                }
            return DeletingFileInputStream.open(stagedPlaintext)
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(stagedPlaintext.toPath()) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            if (failure is IOException) throw failure
            throw IOException("Could not decrypt authenticated file", failure)
        }
    }
}

private class DeletingFileInputStream(
    private val file: java.io.File,
    input: InputStream,
) : FilterInputStream(input) {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            super.close()
        } catch (e: Throwable) {
            failure = e
        }
        try {
            Files.deleteIfExists(file.toPath())
        } catch (e: Throwable) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        failure?.let { throw it }
    }

    companion object {
        fun open(file: java.io.File): DeletingFileInputStream {
            val channel = FileChannel.open(file.toPath(), READ, DELETE_ON_CLOSE)
            return try {
                // POSIX removes the name immediately. Other providers retain it until close.
                runCatching { Files.deleteIfExists(file.toPath()) }
                DeletingFileInputStream(
                    file = file,
                    input = Channels.newInputStream(channel),
                )
            } catch (failure: Throwable) {
                runCatching { channel.close() }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                throw failure
            }
        }
    }
}
