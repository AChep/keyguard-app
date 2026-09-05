package com.artemchep.keyguard.common.service.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.KdbxBinaryContentVisitor
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.visitBinaryContents
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseStorage
import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.webdav.WebDavClientFactory
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.util.foundation.crypto.HashState
import com.artemchep.keyguard.util.foundation.crypto.createSha256
import com.artemchep.keyguard.util.io.spool.ByteSnapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import okio.Buffer
import okio.Source as OkioSource

internal const val KEEPASS_ATTACHMENT_MEMORY_BYTES: Long = 2L * 1024L * 1024L
internal const val KEEPASS_ATTACHMENT_MAXIMUM_BYTES: Long = 512L * 1024L * 1024L
private const val KEEPASS_ATTACHMENT_TRANSFER_BYTES = 64 * 1024

internal fun interface KeePassAttachmentStorageFactory {
    fun create(token: KeePassToken): KeePassDatabaseStorage
}

internal class DefaultKeePassAttachmentStorageFactory(
    private val fileService: FileService,
    private val webDavClientFactory: WebDavClientFactory,
) : KeePassAttachmentStorageFactory {
    override fun create(token: KeePassToken): KeePassDatabaseStorage =
        createKeePassDatabaseStorage(
            fileService = fileService,
            token = token,
            webDavClientFactory = webDavClientFactory,
        )
}

internal class StagedKeePassAttachment(
    private val snapshot: ByteSnapshot,
) : AutoCloseable {
    val size: Long
        get() = snapshot.size

    fun source(): Source = snapshot.openSource()

    override fun close() = snapshot.close()
}

internal class KeePassAttachmentReader(
    private val base64Service: Base64Service,
    private val storageFactory: KeePassAttachmentStorageFactory,
    private val stagingSpoolFactory: StagingSpoolFactory,
) {
    suspend fun read(
        token: KeePassToken,
        contentHash: ByteArray,
        expectedSize: Long?,
    ): StagedKeePassAttachment {
        require(contentHash.isNotEmpty()) {
            "KeePass attachment hash must not be empty."
        }
        expectedSize?.let { size ->
            require(size in 0L..KEEPASS_ATTACHMENT_MAXIMUM_BYTES) {
                "KeePass attachment exceeds the supported size limit."
            }
        }

        val keyData = token.key.keyBase64
            ?.let(base64Service::decode)
        val credentials = try {
            createKeePassCredentials(
                passphrase = token.key.toPassphraseOrNull(),
                keyData = keyData,
            )
        } finally {
            keyData?.fill(0)
        }
        val storage = storageFactory.create(token)
        val coroutineContext = currentCoroutineContext()
        return storage.readWithDecodeRetry { source ->
            KeePassAttachmentExtractor(stagingSpoolFactory).extract(
                source = source,
                credentials = credentials,
                contentHash = contentHash,
                expectedSize = expectedSize,
                checkCancellation = coroutineContext::ensureActive,
            )
        }
    }
}

internal class KeePassAttachmentExtractor(
    private val stagingSpoolFactory: StagingSpoolFactory,
) {
    fun extract(
        source: Source,
        credentials: app.keemobile.kotpass.database.Credentials,
        contentHash: ByteArray,
        expectedSize: Long?,
        checkCancellation: () -> Unit = {},
    ): StagedKeePassAttachment {
        var matched: ByteSnapshot? = null
        try {
            KeePassDatabase.visitBinaryContents(
                source = source,
                credentials = credentials,
                cipherProviders = keePassCipherProviders,
                checkCancellation = checkCancellation,
                visitor = KdbxBinaryContentVisitor { binary, declaredLength ->
                    if (matched != null) return@KdbxBinaryContentVisitor
                    if (
                        expectedSize != null &&
                        declaredLength != null &&
                        declaredLength != expectedSize
                    ) {
                        // Cannot be the requested attachment; leave the source
                        // unread so the decoder drains it without any staging.
                        return@KdbxBinaryContentVisitor
                    }
                    val candidate = stageCandidate(
                        source = binary,
                        expectedSize = expectedSize,
                        checkCancellation = checkCancellation,
                    ) ?: return@KdbxBinaryContentVisitor
                    try {
                        if (!candidate.hash.contentEquals(contentHash)) {
                            candidate.snapshot.close()
                            return@KdbxBinaryContentVisitor
                        }
                        if (expectedSize != null && candidate.snapshot.size != expectedSize) {
                            candidate.snapshot.close()
                            throw IllegalStateException(
                                "KeePass attachment size does not match its metadata.",
                            )
                        }
                        matched = candidate.snapshot
                    } finally {
                        candidate.hash.fill(0)
                    }
                },
            )
            val snapshot = matched
                ?: throw IllegalStateException("Could not find requested KeePass attachment data.")
            matched = null
            return StagedKeePassAttachment(snapshot)
        } finally {
            matched?.close()
        }
    }

    /**
     * Stages one binary and computes its hash. Returns `null` without reading
     * the binary to its end when it grows past [expectedSize] and therefore
     * can no longer be the requested attachment.
     */
    // The generic catch wipes the computed hash before rethrowing whatever
    // made the seal fail.
    @Suppress("TooGenericExceptionCaught")
    private fun stageCandidate(
        source: OkioSource,
        expectedSize: Long?,
        checkCancellation: () -> Unit,
    ): Candidate? {
        val writer = stagingSpoolFactory.create(
            purpose = StagingPurpose.KeePassAttachmentPlaintext,
            limits = SpoolLimits(
                memoryBytes = KEEPASS_ATTACHMENT_MEMORY_BYTES,
                maximumBytes = KEEPASS_ATTACHMENT_MAXIMUM_BYTES,
            ),
            limitExceeded = { limit ->
                IOException(
                    "KeePass attachment exceeds the supported staging limit of $limit bytes",
                )
            },
        )
        return writer.use { store ->
            val digest = createSha256()
            try {
                val withinExpectedSize = store.sink().use { output ->
                    copyAndHash(
                        source = source,
                        output = output,
                        digest = digest,
                        byteLimit = expectedSize,
                        checkCancellation = checkCancellation,
                    )
                }
                if (!withinExpectedSize) return@use null
                val hash = digest.doFinal()
                val snapshot = try {
                    store.seal()
                } catch (e: Throwable) {
                    hash.fill(0)
                    throw e
                }
                Candidate(
                    hash = hash,
                    snapshot = snapshot,
                )
            } finally {
                digest.close()
            }
        }
    }
}

private class Candidate(
    val hash: ByteArray,
    val snapshot: ByteSnapshot,
)

/**
 * Returns `false` once the copied byte count exceeds [byteLimit], leaving the
 * source only partially consumed.
 */
private fun copyAndHash(
    source: OkioSource,
    output: Sink,
    digest: HashState,
    byteLimit: Long?,
    checkCancellation: () -> Unit,
): Boolean {
    val transfer = Buffer()
    val chunk = ByteArray(KEEPASS_ATTACHMENT_TRANSFER_BYTES)
    var copied = 0L
    try {
        while (true) {
            checkCancellation()
            val read = source.read(
                sink = transfer,
                byteCount = KEEPASS_ATTACHMENT_TRANSFER_BYTES.toLong(),
            )
            if (read == -1L) return true
            copied += read
            if (byteLimit != null && copied > byteLimit) return false
            while (true) {
                val length = transfer.read(chunk, 0, chunk.size)
                if (length <= 0) break
                digest.update(chunk, 0, length)
                output.write(chunk, 0, length)
            }
        }
    } finally {
        chunk.fill(0)
        transfer.clear()
    }
}
