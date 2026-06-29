package app.keemobile.kotpass.database

import app.keemobile.kotpass.constants.Defaults
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.cryptography.KeyTransform
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.BaseKdfProvider
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.DatabaseHeader.Compression
import app.keemobile.kotpass.database.header.DatabaseInnerHeader
import app.keemobile.kotpass.database.header.Signature
import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.teeBufferStream
import app.keemobile.kotpass.io.BufferedStream
import app.keemobile.kotpass.io.gunzip
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.XmlContentParser
import kotlinx.io.Source
import kotlinx.io.readByteArray
import okio.Buffer
import okio.ByteString.Companion.toByteString

fun KeePassDatabase.Companion.decode(
    source: Source,
    credentials: Credentials,
    validateHashes: Boolean = true,
    contentParser: XmlContentParser = DefaultXmlContentParser,
    cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    kdfProvider: KdfProvider = BaseKdfProvider,
    untitledLabel: String = Defaults.UntitledLabel
): KeePassDatabase =
    decode(
        data = source.readByteArray(),
        credentials = credentials,
        validateHashes = validateHashes,
        contentParser = contentParser,
        cipherProviders = cipherProviders,
        kdfProvider = kdfProvider,
        untitledLabel = untitledLabel,
    )

fun KeePassDatabase.Companion.decode(
    data: ByteArray,
    credentials: Credentials,
    validateHashes: Boolean = true,
    contentParser: XmlContentParser = DefaultXmlContentParser,
    cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    kdfProvider: KdfProvider = BaseKdfProvider,
    untitledLabel: String = Defaults.UntitledLabel
): KeePassDatabase {
    val headerBuffer = Buffer()
    val source = Buffer().write(data).teeBufferStream(headerBuffer)

    try {
        val header = DatabaseHeader.readFrom(source)

        if (header.signature.base != Signature.Base) {
            throw FormatError.UnknownFormat("File has unexpected signature.")
        }
        if (header.signature.secondary != Signature.Secondary ||
            header.version.major < MinSupportedVersion ||
            header.version.major > MaxSupportedVersion
        ) {
            throw FormatError.UnsupportedVersion("File version is not supported.")
        }
        val rawHeaderData = headerBuffer.snapshot()
        val transformedKey = KeyTransform.transformedKey(kdfProvider, header, credentials)

        return when (header) {
            is DatabaseHeader.Ver3x -> {
                val saltGenerator = with(header) {
                    EncryptionSaltGenerator.create(innerRandomStreamId, innerRandomStreamKey)
                }
                val content = decryptRawContent(header, source, transformedKey, cipherProviders)
                    .let {
                        contentParser.unmarshalContent(it) { meta ->
                            XmlContext.Decode(
                                version = header.version,
                                encryption = saltGenerator,
                                binaries = meta.binaries,
                                untitledLabel = untitledLabel
                            )
                        }
                    }
                val headerHash = content.meta.headerHash

                if (validateHashes && headerHash != null && headerHash != rawHeaderData.sha256()) {
                    throw FormatError.InvalidHeader("HeaderHash value does not match Sha256 of the header.")
                }
                KeePassDatabase.Ver3x(credentials, header, content)
            }
            is DatabaseHeader.Ver4x -> {
                val expectedSha256 = source.readByteString(32)
                val expectedHmacSha256 = source.readByteString(32)

                if (validateHashes) {
                    if (rawHeaderData.sha256() != expectedSha256) {
                        throw FormatError.InvalidHeader("Header's Sha256 does not match.")
                    }

                    val hmacKey = KeyTransform.hmacKey(
                        masterSeed = header.masterSeed.toByteArray(),
                        transformedKey = transformedKey
                    )
                    val hmacSha256 = rawHeaderData.hmacSha256(hmacKey.toByteString())
                    if (hmacSha256 != expectedHmacSha256) {
                        throw CryptoError.InvalidKey("Wrong key used for decryption.")
                    }
                }

                val rawContentBuffer = Buffer()
                    .write(decryptRawContent(header, source, transformedKey, cipherProviders))
                val innerHeader = DatabaseInnerHeader.readFrom(rawContentBuffer)
                val saltGenerator = EncryptionSaltGenerator.create(
                    id = innerHeader.randomStreamId,
                    key = innerHeader.randomStreamKey
                )
                val content = rawContentBuffer
                    .readByteArray()
                    .let {
                        contentParser.unmarshalContent(it) {
                            XmlContext.Decode(
                                version = header.version,
                                encryption = saltGenerator,
                                binaries = innerHeader.binaries,
                                untitledLabel = untitledLabel
                            )
                        }
                    }

                KeePassDatabase.Ver4x(credentials, header, content, innerHeader)
            }
        }
    } finally {
        source.close()
    }
}

fun KeePassDatabase.Companion.decodeFromXml(
    xmlData: ByteArray,
    credentials: Credentials,
    contentParser: XmlContentParser = DefaultXmlContentParser,
    untitledLabel: String = Defaults.UntitledLabel
): KeePassDatabase {
    val header = DatabaseHeader.Ver4x.create()
    var innerHeader = DatabaseInnerHeader.create()
    val saltGenerator = EncryptionSaltGenerator.create(
        id = innerHeader.randomStreamId,
        key = innerHeader.randomStreamKey
    )
    var content = contentParser.unmarshalContent(xmlData) { meta ->
        XmlContext.Decode(
            version = header.version,
            encryption = saltGenerator,
            binaries = meta.binaries,
            untitledLabel = untitledLabel
        )
    }
    innerHeader = innerHeader.copy(
        binaries = content.meta.binaries
    )
    content = content.copy(
        meta = content.meta.copy(binaries = linkedMapOf())
    )

    return KeePassDatabase.Ver4x(credentials, header, content, innerHeader)
}

private fun decryptRawContent(
    header: DatabaseHeader,
    source: BufferedStream,
    transformedKey: ByteArray,
    cipherProviders: List<CipherProvider>
): ByteArray {
    val cipher = cipherProviders
        .firstOrNull { it.uuid == header.cipherId }
        ?: throw FormatError.InvalidHeader("Unsupported cipher ID (${header.cipherId}).")
    val masterSeed = header.masterSeed.toByteArray()
    val decryptedContent = when (header) {
        is DatabaseHeader.Ver3x -> {
            val contentBlocks = cipher.decrypt(
                key = KeyTransform.masterKey(masterSeed, transformedKey),
                iv = header.encryptionIV.toByteArray(),
                data = source.readByteArray()
            )
            val streamStartBytes = header.streamStartBytes
            if (!streamStartBytes.rangeEquals(0, contentBlocks, 0, streamStartBytes.size)) {
                throw FormatError.InvalidContent("Database content could be corrupted or cannot be decrypted.")
            }

            ContentBlocks.readContentBlocksVer3x(
                Buffer().write(
                    contentBlocks,
                    streamStartBytes.size,
                    contentBlocks.size - streamStartBytes.size
                )
            )
        }
        is DatabaseHeader.Ver4x -> {
            val encryptedContent = ContentBlocks
                .readContentBlocksVer4x(source, masterSeed, transformedKey)

            cipher.decrypt(
                key = KeyTransform.masterKey(masterSeed, transformedKey),
                iv = header.encryptionIV.toByteArray(),
                data = encryptedContent
            )
        }
    }

    return when (header.compression) {
        Compression.None -> decryptedContent
        Compression.GZip -> decryptedContent.gunzip()
    }
}
