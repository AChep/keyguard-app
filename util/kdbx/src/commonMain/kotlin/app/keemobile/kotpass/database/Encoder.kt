package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.cryptography.KeyTransform
import app.keemobile.kotpass.cryptography.SecureRandom
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.BaseKdfProvider
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.regenerateVectors
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.gzip
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.XmlContentParser
import kotlinx.io.Sink
import kotlinx.io.write
import okio.Buffer
import okio.BufferedSink
import okio.ByteString.Companion.toByteString

fun KeePassDatabase.encode(
    contentParser: XmlContentParser = DefaultXmlContentParser,
    cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    kdfProvider: KdfProvider = BaseKdfProvider,
    random: SecureRandom = SecureRandom()
) = regenerateVectors(random, cipherProviders)
    .encodeAsBinary(contentParser, cipherProviders, kdfProvider)

fun KeePassDatabase.encodeTo(
    sink: Sink,
    contentParser: XmlContentParser = DefaultXmlContentParser,
    cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    kdfProvider: KdfProvider = BaseKdfProvider,
    random: SecureRandom = SecureRandom()
) {
    sink.write(
        encode(
            contentParser = contentParser,
            cipherProviders = cipherProviders,
            kdfProvider = kdfProvider,
            random = random,
        )
    )
    sink.flush()
}

private fun KeePassDatabase.encodeAsBinary(
    contentParser: XmlContentParser = DefaultXmlContentParser,
    cipherProviders: List<CipherProvider>,
    kdfProvider: KdfProvider
): ByteArray {
    val transformedKey = KeyTransform.transformedKey(kdfProvider, header, credentials)
    val headerBuffer = Buffer().apply {
        header.writeTo(this)
    }
    val headerHash = headerBuffer.sha256()

    var rawContent = when (this) {
        is KeePassDatabase.Ver3x -> {
            val innerEncryption = EncryptionSaltGenerator.create(
                id = header.innerRandomStreamId,
                key = header.innerRandomStreamKey
            )
            val context = XmlContext.Encode.Encrypted(
                version = header.version,
                innerEncryption = innerEncryption,
                binaries = binaries
            )
            val newMeta = content.meta.copy(headerHash = headerHash)

            contentParser
                .marshalContent(context, content.copy(meta = newMeta))
                .encodeToByteArray()
        }
        is KeePassDatabase.Ver4x -> {
            val innerEncryption = EncryptionSaltGenerator.create(
                id = innerHeader.randomStreamId,
                key = innerHeader.randomStreamKey
            )
            val context = XmlContext.Encode.Encrypted(
                version = header.version,
                innerEncryption = innerEncryption,
                binaries = binaries
            )
            val hmacKey = KeyTransform.hmacKey(
                masterSeed = header.masterSeed.toByteArray(),
                transformedKey = transformedKey
            )
            val hmacSha256 = headerBuffer.hmacSha256(hmacKey.toByteString())
            headerBuffer.write(headerHash)
            headerBuffer.write(hmacSha256)

            val rawContent = contentParser
                .marshalContent(context, content)
                .encodeToByteArray()

            val contentBuffer = Buffer()
            innerHeader.writeTo(contentBuffer)
            contentBuffer.write(rawContent)

            contentBuffer.readByteArray()
        }
    }

    if (header.compression == DatabaseHeader.Compression.GZip) {
        rawContent = rawContent.gzip()
    }

    return Buffer()
        .apply {
            write(headerBuffer.snapshot().toByteArray())
            writeEncryptedContent(header, rawContent, transformedKey, cipherProviders)
        }
        .readByteArray()
}

fun KeePassDatabase.encodeAsXml(
    contentParser: XmlContentParser = DefaultXmlContentParser
): String = contentParser.marshalContent(
    context = XmlContext.Encode.Plain(
        version = header.version,
        binaries = binaries,
        memoryProtectionFlags = content.meta.memoryProtection
    ),
    content = content,
    pretty = true
)

private fun BufferedSink.writeEncryptedContent(
    header: DatabaseHeader,
    rawContent: ByteArray,
    transformedKey: ByteArray,
    cipherProviders: List<CipherProvider>
) {
    val cipher = cipherProviders
        .firstOrNull { it.uuid == header.cipherId }
        ?: throw FormatError.InvalidHeader("Unsupported cipher ID (${header.cipherId}).")
    val masterSeed = header.masterSeed.toByteArray()

    when (header) {
        is DatabaseHeader.Ver3x -> {
            val contentBuffer = Buffer()
            contentBuffer.write(header.streamStartBytes)
            ContentBlocks.writeContentBlocksVer3x(contentBuffer, rawContent)

            val encryptedContent = cipher.encrypt(
                key = KeyTransform.masterKey(masterSeed, transformedKey),
                iv = header.encryptionIV.toByteArray(),
                data = contentBuffer.readByteArray()
            )
            write(encryptedContent)
        }
        is DatabaseHeader.Ver4x -> {
            val encryptedContent = cipher.encrypt(
                key = KeyTransform.masterKey(masterSeed, transformedKey),
                iv = header.encryptionIV.toByteArray(),
                data = rawContent
            )
            ContentBlocks.writeContentBlocksVer4x(
                sink = this,
                contentData = encryptedContent,
                masterSeed = masterSeed,
                transformedKey = transformedKey
            )
        }
    }
}
