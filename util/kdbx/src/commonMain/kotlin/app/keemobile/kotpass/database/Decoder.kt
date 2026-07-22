package app.keemobile.kotpass.database

import app.keemobile.kotpass.constants.Defaults
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.cryptography.KeyTransform
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.BaseKdfProvider
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.CipherSource
import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.DatabaseHeader.Compression
import app.keemobile.kotpass.database.header.DatabaseInnerHeader
import app.keemobile.kotpass.database.header.Signature
import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.errors.KeyfileError
import app.keemobile.kotpass.extensions.teeBufferStream
import app.keemobile.kotpass.io.KotlinxSourceAdapter
import app.keemobile.kotpass.io.LimitedSource
import app.keemobile.kotpass.io.STREAM_BUFFER_SIZE
import app.keemobile.kotpass.io.closeOnFailure
import app.keemobile.kotpass.io.gunzipSource
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.XmlContentParser
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.toByteString
import okio.Source
import okio.buffer
import okio.use
import kotlinx.io.Source as KotlinxSource

fun KeePassDatabase.Companion.decode(
    source: KotlinxSource,
    credentials: Credentials,
    validateHashes: Boolean = true,
    contentParser: XmlContentParser = DefaultXmlContentParser,
    cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    kdfProvider: KdfProvider = BaseKdfProvider,
    untitledLabel: String = Defaults.UntitledLabel,
    limits: KdbxReadLimits = KdbxReadLimits.Default,
): KeePassDatabase =
    decodeSource(
        input = KotlinxSourceAdapter(source),
        credentials = credentials,
        validateHashes = validateHashes,
        contentParser = contentParser,
        cipherProviders = cipherProviders,
        kdfProvider = kdfProvider,
        untitledLabel = untitledLabel,
        limits = limits,
    )

fun KeePassDatabase.Companion.decode(
    data: ByteArray,
    credentials: Credentials,
    validateHashes: Boolean = true,
    contentParser: XmlContentParser = DefaultXmlContentParser,
    cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    kdfProvider: KdfProvider = BaseKdfProvider,
    untitledLabel: String = Defaults.UntitledLabel,
    limits: KdbxReadLimits = KdbxReadLimits.Default,
): KeePassDatabase = decodeSource(
    input = Buffer().write(data),
    credentials = credentials,
    validateHashes = validateHashes,
    contentParser = contentParser,
    cipherProviders = cipherProviders,
    kdfProvider = kdfProvider,
    untitledLabel = untitledLabel,
    limits = limits,
)

private fun KeePassDatabase.Companion.decodeSource(
    input: Source,
    credentials: Credentials,
    validateHashes: Boolean,
    contentParser: XmlContentParser,
    cipherProviders: List<CipherProvider>,
    kdfProvider: KdfProvider,
    untitledLabel: String,
    limits: KdbxReadLimits,
): KeePassDatabase {
    val headerBuffer = Buffer()
    val source = input.teeBufferStream(headerBuffer)

    try {
        val header = DatabaseHeader.readFrom(source)
        validateHeader(header)

        val rawHeaderData = headerBuffer.snapshot()
        val transformedKey = KeyTransform.transformedKey(kdfProvider, header, credentials)
        val cipher = resolveCipher(header, cipherProviders)
        val masterSeed = header.masterSeed.toByteArray()
        val masterKey = KeyTransform.masterKey(masterSeed, transformedKey)

        return try {
            when (header) {
                is DatabaseHeader.Ver3x -> {
                    decodeVer3x(
                        header = header,
                        source = source,
                        rawHeaderData = rawHeaderData,
                        credentials = credentials,
                        validateHashes = validateHashes,
                        contentParser = contentParser,
                        cipher = cipher,
                        masterKey = masterKey,
                        untitledLabel = untitledLabel,
                        limits = limits,
                    )
                }

                is DatabaseHeader.Ver4x -> {
                    decodeVer4x(
                        header = header,
                        source = source,
                        rawHeaderData = rawHeaderData,
                        credentials = credentials,
                        validateHashes = validateHashes,
                        contentParser = contentParser,
                        cipher = cipher,
                        transformedKey = transformedKey,
                        masterSeed = masterSeed,
                        masterKey = masterKey,
                        untitledLabel = untitledLabel,
                        limits = limits,
                    )
                }
            }
        } finally {
            masterKey.fill(0)
            transformedKey.fill(0)
            masterSeed.fill(0)
        }
    } catch (error: FormatError) {
        throw error
    } catch (error: CryptoError) {
        throw error
    } catch (error: KeyfileError) {
        throw error
    } catch (error: Exception) {
        // Malformed, attacker-controlled data may reach lower-level parsers or
        // ciphers. Do not leak raw runtime exceptions across the decode API.
        throw FormatError.InvalidContent(
            "Failed to decode the database: ${error.message ?: error::class.simpleName}",
        )
    } finally {
        source.close()
    }
}

private fun decodeVer3x(
    header: DatabaseHeader.Ver3x,
    source: Source,
    rawHeaderData: okio.ByteString,
    credentials: Credentials,
    validateHashes: Boolean,
    contentParser: XmlContentParser,
    cipher: CipherProvider,
    masterKey: ByteArray,
    untitledLabel: String,
    limits: KdbxReadLimits,
): KeePassDatabase.Ver3x {
    val decrypted = CipherSource(
        upstream = source,
        session = cipher.createDecryptor(masterKey, header.encryptionIV.toByteArray()),
    ).buffer()
    return decrypted.use { decryptedSource ->
        val streamStartBytes =
            decryptedSource.readByteString(header.streamStartBytes.size.toLong())
        if (streamStartBytes != header.streamStartBytes) {
            throw CryptoError.InvalidKey(
                "Wrong key used for decryption or database is corrupted.",
            )
        }

        val blocks = ContentBlocks.ver3Source(
            source = decryptedSource,
            maximumBlockSize = limits.maximumBlockSize,
        )
        val contentSource = blocks.decodeCompression(header.compression, limits).buffer()
        val saltGenerator = EncryptionSaltGenerator.create(
            header.innerRandomStreamId,
            header.innerRandomStreamKey,
        )
        val content =
            try {
                val plaintext = contentSource
                val parsed =
                    contentParser.unmarshalContent(plaintext, saltGenerator) { meta ->
                        XmlContext.Decode(
                            version = header.version,
                            encryption = saltGenerator,
                            binaries = meta.binaries,
                            untitledLabel = untitledLabel,
                        )
                    }
                plaintext.drainAndVerify()
                parsed
            } finally {
                contentSource.close()
            }

        val headerHash = content.meta.headerHash
        if (validateHashes && headerHash != null && headerHash != rawHeaderData.sha256()) {
            throw FormatError.InvalidHeader("HeaderHash value does not match Sha256 of the header.")
        }
        KeePassDatabase.Ver3x(credentials, header, content)
    }
}

private fun decodeVer4x(
    header: DatabaseHeader.Ver4x,
    source: app.keemobile.kotpass.io.BufferedStream,
    rawHeaderData: okio.ByteString,
    credentials: Credentials,
    validateHashes: Boolean,
    contentParser: XmlContentParser,
    cipher: CipherProvider,
    transformedKey: ByteArray,
    masterSeed: ByteArray,
    masterKey: ByteArray,
    untitledLabel: String,
    limits: KdbxReadLimits,
): KeePassDatabase.Ver4x {
    val expectedSha256 = source.readByteString(32)
    val expectedHmacSha256 = source.readByteString(32)

    if (validateHashes) {
        if (rawHeaderData.sha256() != expectedSha256) {
            throw FormatError.InvalidHeader("Header's Sha256 does not match.")
        }
        val hmacKey = KeyTransform.hmacKey(masterSeed, transformedKey)
        try {
            if (rawHeaderData.hmacSha256(hmacKey.toByteString()) != expectedHmacSha256) {
                throw CryptoError.InvalidKey("Wrong key used for decryption.")
            }
        } finally {
            hmacKey.fill(0)
        }
    }

    val blocks =
        ContentBlocks.ver4Source(
            source = source,
            masterSeed = masterSeed,
            transformedKey = transformedKey,
            maximumBlockSize = limits.maximumBlockSize,
        )
    val decryptor = blocks.closeOnFailure {
        cipher.createDecryptor(masterKey, header.encryptionIV.toByteArray())
    }
    val decrypted =
        CipherSource(
            upstream = blocks,
            session = decryptor,
        )
    val contentSource = decrypted.decodeCompression(header.compression, limits).buffer()
    return try {
        val plaintext = contentSource
        val innerHeader = DatabaseInnerHeader.readFrom(plaintext)
        val saltGenerator =
            EncryptionSaltGenerator.create(
                id = innerHeader.randomStreamId,
                key = innerHeader.randomStreamKey,
            )
        val content =
            contentParser.unmarshalContent(plaintext, saltGenerator) {
                XmlContext.Decode(
                    version = header.version,
                    encryption = saltGenerator,
                    binaries = innerHeader.binaries,
                    untitledLabel = untitledLabel,
                )
            }
        plaintext.drainAndVerify()
        KeePassDatabase.Ver4x(credentials, header, content, innerHeader)
    } finally {
        contentSource.close()
    }
}

private fun validateHeader(header: DatabaseHeader) {
    if (header.signature.base != Signature.Base) {
        throw FormatError.UnknownFormat("File has unexpected signature.")
    }
    if (header.signature.secondary != Signature.Secondary ||
        header.version.major < KeePassDatabase.MinSupportedVersion ||
        header.version.major > KeePassDatabase.MaxSupportedVersion
    ) {
        throw FormatError.UnsupportedVersion("File version is not supported.")
    }
}

internal fun resolveCipher(
    header: DatabaseHeader,
    cipherProviders: List<CipherProvider>,
): CipherProvider {
    val cipher =
        cipherProviders.firstOrNull { it.uuid == header.cipherId }
            ?: throw FormatError.InvalidHeader("Unsupported cipher ID (${header.cipherId}).")
    if (header.encryptionIV.size != cipher.ivLength.toInt()) {
        throw FormatError.InvalidHeader(
            "Encryption IV length (${header.encryptionIV.size}) does not match " +
                    "the cipher's expected length (${cipher.ivLength}).",
        )
    }
    return cipher
}

private fun Source.decodeCompression(
    compression: Compression,
    limits: KdbxReadLimits,
): Source =
    when (compression) {
        Compression.None -> LimitedSource(this, limits.maximumContentBytes)
        Compression.GZip -> gunzipSource(limits.maximumContentBytes)
    }

private fun BufferedSource.drainAndVerify() {
    val discard = Buffer()
    while (true) {
        val read = read(discard, STREAM_BUFFER_SIZE.toLong())
        if (read == -1L) break
        discard.clear()
    }
}

fun KeePassDatabase.Companion.decodeFromXml(
    xmlData: ByteArray,
    credentials: Credentials,
    contentParser: XmlContentParser = DefaultXmlContentParser,
    untitledLabel: String = Defaults.UntitledLabel,
): KeePassDatabase =
    decodeFromXml(
        source = Buffer().write(xmlData),
        credentials = credentials,
        contentParser = contentParser,
        untitledLabel = untitledLabel,
    )

fun KeePassDatabase.Companion.decodeFromXml(
    source: BufferedSource,
    credentials: Credentials,
    contentParser: XmlContentParser = DefaultXmlContentParser,
    untitledLabel: String = Defaults.UntitledLabel,
): KeePassDatabase {
    val header = DatabaseHeader.Ver4x.create()
    var innerHeader = DatabaseInnerHeader.create()
    val saltGenerator =
        EncryptionSaltGenerator.create(
            id = innerHeader.randomStreamId,
            key = innerHeader.randomStreamKey,
        )
    var content =
        contentParser.unmarshalContent(source, saltGenerator) { meta ->
            XmlContext.Decode(
                version = header.version,
                encryption = saltGenerator,
                binaries = meta.binaries,
                untitledLabel = untitledLabel,
            )
        }
    innerHeader = innerHeader.copy(binaries = content.meta.binaries)
    content = content.copy(meta = content.meta.copy(binaries = linkedMapOf()))

    return KeePassDatabase.Ver4x(credentials, header, content, innerHeader)
}
