package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.cryptography.KeyTransform
import app.keemobile.kotpass.cryptography.SecureRandom
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.BaseKdfProvider
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.CipherSink
import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.DatabaseInnerHeader
import app.keemobile.kotpass.database.modifiers.regenerateVectors
import app.keemobile.kotpass.io.KotlinxSinkAdapter
import app.keemobile.kotpass.io.closeOnFailure
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.XmlContentParser
import okio.Buffer
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.buffer
import kotlinx.io.Sink as KotlinxSink

/**
 * Opens a [KeePassDatabaseWriter] that encodes this database to a
 * caller-owned sink. The binaries of this database are written first.
 */
fun KeePassDatabase.Ver4x.openWriter(
    sink: KotlinxSink,
    contentParser: XmlContentParser = DefaultXmlContentParser,
    cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    kdfProvider: KdfProvider = BaseKdfProvider,
    random: SecureRandom = SecureRandom(),
): KeePassDatabaseWriter {
    // Regenerating keeps the version of the database.
    val database = regenerateVectors(random, cipherProviders) as KeePassDatabase.Ver4x
    return KeePassDatabaseWriter.open(
        database = database,
        output = KotlinxSinkAdapter(sink).buffer(),
        contentParser = contentParser,
        cipherProviders = cipherProviders,
        kdfProvider = kdfProvider,
    )
}

/**
 * Encodes a KDBX 4 database with binaries written one by one.
 *
 * KDBX 4 stores the binaries ahead of the content, so [writeBinary] copies
 * a binary into the output right away and its source may be released after
 * the call. [finish] writes the content, which may reference only the written
 * binaries.
 *
 * If encoding fails, or the writer is closed before [finish], the sink
 * may contain a partial KDBX payload.
 */
@Suppress("TooGenericExceptionCaught")
class KeePassDatabaseWriter private constructor(
    private val database: KeePassDatabase.Ver4x,
    private val output: BufferedSink,
    private val contentParser: XmlContentParser,
    private val blockSink: ContentBlocks.BlockSink,
    private val cipherSink: CipherSink,
) : AutoCloseable {
    // Inner header + XML -> gzip -> cipher -> v4 blocks.
    private val contentSink = cipherSink.compressedSink(database.header.compression)
    private val writtenBinaries = linkedMapOf<ByteString, BinaryData>()
    private var failed = false
    private var finished = false
    private var closed = false

    init {
        try {
            database.innerHeader.writeFieldsTo(contentSink)
            val binaryWritePlan = BinaryWritePlan.create(database.innerHeader.binaries)
            for ((_, hash, binary) in binaryWritePlan.entries) {
                writeBinary(hash, binary)
            }
        } catch (e: Throwable) {
            try {
                close()
            } catch (closeError: Throwable) {
                e.addSuppressed(closeError)
            }
            throw e
        }
    }

    /**
     * Copies the binary into the output, unless a binary
     * with the same hash has already been written.
     */
    fun writeBinary(binary: BinaryData) = writeBinary(binary.hash, binary)

    private fun writeBinary(
        hash: ByteString,
        binary: BinaryData,
    ) = write {
        if (hash !in writtenBinaries) {
            DatabaseInnerHeader.writeBinaryTo(contentSink, binary)
            writtenBinaries[hash] = binary
        }
    }

    /**
     * Writes the content and completes the database.
     */
    fun finish(content: DatabaseContent) = write {
        finished = true
        val innerHeader = database.innerHeader
        DatabaseInnerHeader.writeTerminatorTo(contentSink)
        val innerEncryption =
            EncryptionSaltGenerator.create(
                id = innerHeader.randomStreamId,
                key = innerHeader.randomStreamKey,
            )
        val context =
            XmlContext.Encode.Encrypted(
                version = database.header.version,
                innerEncryption = innerEncryption,
                binaryWritePlan = BinaryWritePlan.create(writtenBinaries),
            )
        contentParser.marshalContentTo(context, content, contentSink)

        // Each layer must be finalized before the layer beneath it.
        contentSink.close()
        cipherSink.finish()
        blockSink.finish()
        output.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        // Closing the content sink also releases the
        // compressor of an unfinished database.
        var failure: Throwable? = null
        for (sink in listOf(contentSink, cipherSink, blockSink)) {
            try {
                sink.close()
            } catch (e: Throwable) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw it }
    }

    private inline fun write(block: () -> Unit) {
        check(!closed) { "KeePass database writer is closed" }
        check(!finished) { "KeePass database writer is finished" }
        check(!failed) { "KeePass database writer has failed" }
        try {
            block()
        } catch (e: Throwable) {
            failed = true
            throw e
        }
    }

    internal companion object {
        fun open(
            database: KeePassDatabase.Ver4x,
            output: BufferedSink,
            contentParser: XmlContentParser,
            cipherProviders: List<CipherProvider>,
            kdfProvider: KdfProvider,
        ): KeePassDatabaseWriter {
            val header = database.header
            val transformedKey = KeyTransform.transformedKey(kdfProvider, header, database.credentials)
            val masterSeed = header.masterSeed.toByteArray()
            val masterKey = KeyTransform.masterKey(masterSeed, transformedKey)
            try {
                val cipher = resolveCipher(header, cipherProviders)
                output.writeHeader(header, masterSeed, transformedKey)

                // The sinks keep their own copies of the keys.
                val blockSink = ContentBlocks.ver4Sink(output, masterSeed, transformedKey)
                return blockSink.closeOnFailure {
                    val cipherSink = CipherSink(
                        downstream = blockSink,
                        session = cipher.createEncryptor(masterKey, header.encryptionIV.toByteArray()),
                    )
                    cipherSink.closeOnFailure {
                        KeePassDatabaseWriter(
                            database = database,
                            output = output,
                            contentParser = contentParser,
                            blockSink = blockSink,
                            cipherSink = cipherSink,
                        )
                    }
                }
            } finally {
                masterKey.fill(0)
                transformedKey.fill(0)
                masterSeed.fill(0)
            }
        }

        private fun BufferedSink.writeHeader(
            header: DatabaseHeader.Ver4x,
            masterSeed: ByteArray,
            transformedKey: ByteArray,
        ) {
            val headerBuffer = Buffer().apply { header.writeTo(this) }
            val headerHash = headerBuffer.sha256()
            val hmacKey = KeyTransform.hmacKey(masterSeed, transformedKey)
            try {
                val headerHmac = headerBuffer.hmacSha256(hmacKey.toByteString())
                headerBuffer.write(headerHash)
                headerBuffer.write(headerHmac)
            } finally {
                hmacKey.fill(0)
            }
            write(headerBuffer, headerBuffer.size)
        }
    }
}
