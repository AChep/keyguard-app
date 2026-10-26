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
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.regenerateVectors
import app.keemobile.kotpass.io.KotlinxSinkAdapter
import app.keemobile.kotpass.io.NonClosingSink
import app.keemobile.kotpass.io.gzipSink
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.XmlContentParser
import okio.Buffer
import okio.BufferedSink
import okio.Sink
import okio.buffer
import okio.use
import kotlinx.io.Sink as KotlinxSink

fun KeePassDatabase.encode(
    contentParser: XmlContentParser = DefaultXmlContentParser,
    cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    kdfProvider: KdfProvider = BaseKdfProvider,
    random: SecureRandom = SecureRandom(),
): ByteArray {
    val output = Buffer()
    regenerateVectors(random, cipherProviders).encodeToSink(
        output = output,
        contentParser = contentParser,
        cipherProviders = cipherProviders,
        kdfProvider = kdfProvider,
    )
    return output.readByteArray()
}

/**
 * Encodes directly to a caller-owned sink. If encoding fails after output has
 * begun, the sink may contain a partial KDBX payload; callers replacing an
 * existing database should stage and verify it before publishing.
 */
fun KeePassDatabase.encodeTo(
    sink: KotlinxSink,
    contentParser: XmlContentParser = DefaultXmlContentParser,
    cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    kdfProvider: KdfProvider = BaseKdfProvider,
    random: SecureRandom = SecureRandom(),
) {
    val output = KotlinxSinkAdapter(sink).buffer()
    regenerateVectors(random, cipherProviders).encodeToSink(
        output = output,
        contentParser = contentParser,
        cipherProviders = cipherProviders,
        kdfProvider = kdfProvider,
    )
    output.flush()
}

private fun KeePassDatabase.encodeToSink(
    output: BufferedSink,
    contentParser: XmlContentParser,
    cipherProviders: List<CipherProvider>,
    kdfProvider: KdfProvider,
) {
    when (this) {
        is KeePassDatabase.Ver3x -> encodeVer3xToSink(
            output = output,
            contentParser = contentParser,
            cipherProviders = cipherProviders,
            kdfProvider = kdfProvider,
        )

        is KeePassDatabase.Ver4x -> KeePassDatabaseWriter
            .open(
                database = this,
                output = output,
                contentParser = contentParser,
                cipherProviders = cipherProviders,
                kdfProvider = kdfProvider,
            )
            .use { writer ->
                writer.finish(content)
            }
    }
}

private fun KeePassDatabase.Ver3x.encodeVer3xToSink(
    output: BufferedSink,
    contentParser: XmlContentParser,
    cipherProviders: List<CipherProvider>,
    kdfProvider: KdfProvider,
) {
    val transformedKey = KeyTransform.transformedKey(kdfProvider, header, credentials)
    val masterSeed = header.masterSeed.toByteArray()
    val masterKey = KeyTransform.masterKey(masterSeed, transformedKey)
    val binaryWritePlan = BinaryWritePlan.create(binaries)
    try {
        val cipher = resolveCipher(header, cipherProviders)
        val headerBuffer = Buffer().apply { header.writeTo(this) }
        val headerHash = headerBuffer.sha256()
        output.write(headerBuffer, headerBuffer.size)
        encodeVer3xContent(
            output = output,
            headerHash = headerHash,
            contentParser = contentParser,
            cipher = cipher,
            masterKey = masterKey,
            binaryWritePlan = binaryWritePlan,
        )
        output.flush()
    } finally {
        masterKey.fill(0)
        transformedKey.fill(0)
        masterSeed.fill(0)
    }
}

private fun KeePassDatabase.Ver3x.encodeVer3xContent(
    output: Sink,
    headerHash: okio.ByteString,
    contentParser: XmlContentParser,
    cipher: CipherProvider,
    masterKey: ByteArray,
    binaryWritePlan: BinaryWritePlan,
) {
    val cipherSink =
        CipherSink(
            downstream = output,
            session = cipher.createEncryptor(masterKey, header.encryptionIV.toByteArray()),
        )
    val blockSink = ContentBlocks.ver3Sink(cipherSink)
    try {
        val streamStart = header.streamStartBytes.toByteArray()
        try {
            cipherSink.write(Buffer().write(streamStart), streamStart.size.toLong())
        } finally {
            streamStart.fill(0)
        }

        blockSink.compressedSink(header.compression).use { sink ->
            val innerEncryption =
                EncryptionSaltGenerator.create(
                    id = header.innerRandomStreamId,
                    key = header.innerRandomStreamKey,
                )
            val context =
                XmlContext.Encode.Encrypted(
                    version = header.version,
                    innerEncryption = innerEncryption,
                    binaryWritePlan = binaryWritePlan,
                )
            val newMeta = content.meta.copy(headerHash = headerHash)
            contentParser.marshalContentTo(context, content.copy(meta = newMeta), sink)
        }

        // XML -> gzip -> v3 blocks -> cipher. Each layer must be finalized
        // before the layer beneath it.
        blockSink.finish()
        cipherSink.finish()
    } finally {
        blockSink.close()
        cipherSink.close()
    }
}

/**
 * Closing the returned sink finishes the compressed stream, for
 * example emits the gzip trailer, but leaves this sink writable.
 */
internal fun Sink.compressedSink(
    compression: DatabaseHeader.Compression,
): BufferedSink = when (compression) {
    DatabaseHeader.Compression.None -> NonClosingSink(this).buffer()
    DatabaseHeader.Compression.GZip -> gzipSink().buffer()
}

fun KeePassDatabase.encodeAsXml(
    contentParser: XmlContentParser = DefaultXmlContentParser,
): String = Buffer()
    .apply {
        encodeAsXmlTo(this, contentParser)
    }
    .readUtf8()

fun KeePassDatabase.encodeAsXmlTo(
    sink: BufferedSink,
    contentParser: XmlContentParser = DefaultXmlContentParser,
) {
    val binaryWritePlan = BinaryWritePlan.create(binaries)
    contentParser.marshalContentTo(
        context =
            XmlContext.Encode.Plain(
                version = header.version,
                memoryProtectionFlags = content.meta.memoryProtection,
                binaryWritePlan = binaryWritePlan,
            ),
        content = content,
        sink = sink,
        pretty = true,
    )
}
