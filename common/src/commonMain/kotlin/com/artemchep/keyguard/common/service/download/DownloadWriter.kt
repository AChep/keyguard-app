package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.common.service.crypto.FileEncryptionCodec
import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryPermissions
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicFilePermissions
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationPolicy
import com.artemchep.keyguard.util.io.atomic.AtomicWriteOptions
import com.artemchep.keyguard.util.io.atomic.ExistingParentLinkPolicy
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.ReplacementAccessPolicy
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.atomic.writeFileAtomically
import com.artemchep.keyguard.util.io.spool.stageTo
import com.artemchep.keyguard.util.io.toFileUriString
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered

internal const val DOWNLOAD_PLAINTEXT_MEMORY_LIMIT_BYTES: Long = 2L * 1024L * 1024L
internal const val DOWNLOAD_PLAINTEXT_MAXIMUM_BYTES: Long = 16L * 1024L * 1024L * 1024L

private const val DOWNLOAD_TRANSFER_BUFFER_BYTES = 64 * 1024
private const val MAX_CONSECUTIVE_ZERO_READS = 16

private val DOWNLOAD_ATOMIC_WRITE_OPTIONS = AtomicWriteOptions(
    publication = AtomicPublicationPolicy.Replace(
        access = ReplacementAccessPolicy.UseRequestedPermissions(
            permissions = AtomicFilePermissions.ProcessDefault,
        ),
    ),
    parentDirectories = ParentDirectoryPolicy.CreateMissing(
        permissions = AtomicDirectoryPermissions.ProcessDefault,
    ),
    existingParentLinks = ExistingParentLinkPolicy.Reject,
    synchronization = SynchronizationPolicy.Required(
        SyncLevel.FileSynchronized,
    ),
)

sealed interface DownloadWriter {
    data class LocalPathWriter(
        val destination: AtomicFileDestination,
    ) : DownloadWriter {
        val path
            get() = destination.path
    }

    data class SinkWriter(
        val sink: Sink,
    ) : DownloadWriter
}

fun DownloadWriter.writeBytes(data: ByteArray) {
    when (this) {
        is DownloadWriter.LocalPathWriter -> {
            writeAtomically { sink ->
                sink.write(data)
            }
        }

        is DownloadWriter.SinkWriter -> {
            sink.write(data)
            sink.flush()
        }
    }
}

fun DownloadWriter.locationUri(): String? = when (this) {
    is DownloadWriter.LocalPathWriter -> path.toFileUriString()
    is DownloadWriter.SinkWriter -> null
}

/**
 * Publishes a source whose authenticity and completeness have already been
 * established by its producer. Unlike [writeSource], sink destinations do not
 * need another provisional-plaintext spool.
 */
internal fun DownloadWriter.writeVerifiedSource(
    source: Source,
    checkCancellation: () -> Unit = {},
    onProgress: (downloaded: Long) -> Unit = {},
) {
    // The copy loop already checks for cancellation before every read, so the
    // destination sink does not need its own cancellation-checking wrapper.
    fun copyVerifiedTo(output: Sink) {
        source.copyTo(
            output = output,
            checkCancellation = checkCancellation,
            onProgress = onProgress,
        )
        output.flush()
    }

    when (this) {
        is DownloadWriter.LocalPathWriter -> {
            writeAtomically { output ->
                copyVerifiedTo(output)
            }
        }

        is DownloadWriter.SinkWriter -> {
            copyVerifiedTo(sink)
        }
    }
}

internal fun DownloadWriter.writeSource(
    source: Source,
    key: ByteArray?,
    fileEncryptionCodec: FileEncryptionCodec,
    stagingSpoolFactory: StagingSpoolFactory,
    checkCancellation: () -> Unit = {},
) {
    when (this) {
        is DownloadWriter.LocalPathWriter -> {
            writeAtomically { sink ->
                sink.withCancellationChecks(checkCancellation).use { output ->
                    source.writePlaintextTo(
                        output = output,
                        key = key,
                        fileEncryptionCodec = fileEncryptionCodec,
                        checkCancellation = checkCancellation,
                    )
                }
            }
        }

        is DownloadWriter.SinkWriter -> {
            sink.withCancellationChecks(checkCancellation).use { output ->
                createDownloadPlaintextSpool(stagingSpoolFactory)
                    .stageTo(output) { stagedOutput ->
                        stagedOutput
                            .withCancellationChecks(checkCancellation)
                            .use { checkedStagedOutput ->
                                source.writePlaintextTo(
                                    output = checkedStagedOutput,
                                    key = key,
                                    fileEncryptionCodec = fileEncryptionCodec,
                                    checkCancellation = checkCancellation,
                                )
                            }
                    }
            }
        }
    }
}

private fun createDownloadPlaintextSpool(
    stagingSpoolFactory: StagingSpoolFactory,
) = stagingSpoolFactory.create(
    purpose = StagingPurpose.DownloadSinkPlaintext,
    limits = SpoolLimits(
        memoryBytes = DOWNLOAD_PLAINTEXT_MEMORY_LIMIT_BYTES,
        maximumBytes = DOWNLOAD_PLAINTEXT_MAXIMUM_BYTES,
    ),
    limitExceeded = { limit ->
        IOException("Downloaded plaintext exceeds the supported staging limit of $limit bytes")
    },
)

private fun Source.writePlaintextTo(
    output: Sink,
    key: ByteArray?,
    fileEncryptionCodec: FileEncryptionCodec,
    checkCancellation: () -> Unit,
) {
    checkCancellation()
    if (key != null) {
        fileEncryptionCodec.decrypt(
            input = this,
            output = output,
            key = key,
        )
    } else {
        copyTo(
            output = output,
            checkCancellation = checkCancellation,
        )
    }
    checkCancellation()
}

private fun Source.copyTo(
    output: Sink,
    checkCancellation: () -> Unit,
    onProgress: (downloaded: Long) -> Unit = {},
) {
    val buffer = ByteArray(DOWNLOAD_TRANSFER_BUFFER_BYTES)
    var consecutiveZeroReads = 0
    var downloaded = 0L
    try {
        while (true) {
            checkCancellation()
            val length = readAtMostTo(buffer)
            if (length < 0) break
            if (length == 0) {
                consecutiveZeroReads += 1
                if (consecutiveZeroReads > MAX_CONSECUTIVE_ZERO_READS) {
                    throw IOException("Download source made no progress while reading")
                }
            } else {
                consecutiveZeroReads = 0
                output.write(buffer, 0, length)
                downloaded += length
                onProgress(downloaded)
            }
        }
    } finally {
        buffer.fill(0)
    }
}

private fun DownloadWriter.LocalPathWriter.writeAtomically(
    write: (Sink) -> Unit,
) {
    writeFileAtomically(
        destination = destination,
        options = DOWNLOAD_ATOMIC_WRITE_OPTIONS,
        write = write,
    ).receipt.requireCleanupComplete()
}

private fun Sink.withCancellationChecks(
    checkCancellation: () -> Unit,
): Sink = object : RawSink {
    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        checkCancellation()
        this@withCancellationChecks.write(source, byteCount)
    }

    override fun flush() {
        checkCancellation()
        this@withCancellationChecks.flush()
    }

    // The wrapper owns its buffer, but the caller continues to own the
    // underlying destination sink.
    override fun close() = Unit
}.buffered()
