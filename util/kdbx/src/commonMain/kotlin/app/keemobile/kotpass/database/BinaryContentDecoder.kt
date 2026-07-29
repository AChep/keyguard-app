package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.cryptography.KeyTransform
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.BaseKdfProvider
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.CipherSource
import app.keemobile.kotpass.constants.CrsAlgorithm
import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.DatabaseInnerHeader
import app.keemobile.kotpass.database.header.InnerHeaderFieldId
import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.errors.KeyfileError
import app.keemobile.kotpass.extensions.teeBufferStream
import app.keemobile.kotpass.io.BufferedStream
import app.keemobile.kotpass.io.KotlinxSourceAdapter
import app.keemobile.kotpass.io.LimitedSource
import app.keemobile.kotpass.io.MAX_DECOMPRESSED_SIZE
import app.keemobile.kotpass.io.STREAM_BUFFER_SIZE
import app.keemobile.kotpass.io.closeOnFailure
import app.keemobile.kotpass.xml.XmlBinaryContentVisitor
import app.keemobile.kotpass.xml.visitXmlBinaryContents
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.Source
import okio.Timeout
import okio.buffer
import okio.use
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.Source as KotlinxSource

fun interface KdbxBinaryContentVisitor {
    /**
     * Visits one uncompressed attachment body. [source] is valid only for the
     * duration of this callback and must not escape it; any unread remainder
     * is drained by the decoder.
     *
     * [declaredLength] is the exact byte length when the container declares it
     * upfront (KDBX 4 inner-header binaries), or `null` when the size is only
     * known once the stream ends (XML-embedded binaries).
     */
    fun visit(source: Source, declaredLength: Long?)
}

/**
 * Streams every binary body in a KDBX without constructing a database model.
 *
 * The complete container and XML document are consumed and verified before
 * this function returns. KDBX 3 pooled/inline binaries and KDBX 4 inner-header
 * and inline binaries are all visited as uncompressed plaintext streams.
 */
fun KeePassDatabase.Companion.visitBinaryContents(
    source: KotlinxSource,
    credentials: Credentials,
    validateHashes: Boolean = true,
    visitor: KdbxBinaryContentVisitor,
    cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    kdfProvider: KdfProvider = BaseKdfProvider,
    limits: KdbxReadLimits = KdbxReadLimits.Default,
    checkCancellation: () -> Unit = {},
) {
    val adapter = KotlinxSourceAdapter(source)
    val checkedInput = CancellationCheckingSource(
        delegate = adapter,
        checkCancellation = checkCancellation,
    )
    try {
        visitBinaryContentsSource(
            input = checkedInput,
            credentials = credentials,
            validateHashes = validateHashes,
            visitor = visitor,
            cipherProviders = cipherProviders,
            kdfProvider = kdfProvider,
            limits = limits,
            checkCancellation = checkCancellation,
        )
    } catch (error: Throwable) {
        throw adapter.readFailure ?: error
    }
}

private fun KeePassDatabase.Companion.visitBinaryContentsSource(
    input: Source,
    credentials: Credentials,
    validateHashes: Boolean,
    visitor: KdbxBinaryContentVisitor,
    cipherProviders: List<CipherProvider>,
    kdfProvider: KdfProvider,
    limits: KdbxReadLimits,
    checkCancellation: () -> Unit,
) {
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
        try {
            when (header) {
                is DatabaseHeader.Ver3x -> visitBinaryContentsVer3x(
                    header = header,
                    source = source,
                    rawHeaderData = rawHeaderData,
                    validateHashes = validateHashes,
                    visitor = visitor,
                    cipher = cipher,
                    masterKey = masterKey,
                    limits = limits,
                    checkCancellation = checkCancellation,
                )

                is DatabaseHeader.Ver4x -> visitBinaryContentsVer4x(
                    header = header,
                    source = source,
                    rawHeaderData = rawHeaderData,
                    validateHashes = validateHashes,
                    visitor = visitor,
                    cipher = cipher,
                    transformedKey = transformedKey,
                    masterSeed = masterSeed,
                    masterKey = masterKey,
                    limits = limits,
                    checkCancellation = checkCancellation,
                )
            }
        } finally {
            masterKey.fill(0)
            transformedKey.fill(0)
            masterSeed.fill(0)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: FormatError) {
        throw error
    } catch (error: CryptoError) {
        throw error
    } catch (error: KeyfileError) {
        throw error
    } catch (error: Exception) {
        throw FormatError.InvalidContent(
            "Failed to inspect database binaries: " +
                (error.message ?: error::class.simpleName),
        )
    } finally {
        source.close()
    }
}

private fun visitBinaryContentsVer3x(
    header: DatabaseHeader.Ver3x,
    source: Source,
    rawHeaderData: ByteString,
    validateHashes: Boolean,
    visitor: KdbxBinaryContentVisitor,
    cipher: CipherProvider,
    masterKey: ByteArray,
    limits: KdbxReadLimits,
    checkCancellation: () -> Unit,
) {
    val decrypted = CipherSource(
        upstream = source,
        session = cipher.createDecryptor(masterKey, header.encryptionIV.toByteArray()),
    ).buffer()
    decrypted.use { decryptedSource ->
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
        val headerHash = try {
            visitXmlBinaryContents(
                source = contentSource,
                innerEncryption = saltGenerator,
                visitor = XmlBinaryContentVisitor(visitor::visit),
                checkCancellation = checkCancellation,
            ).also {
                contentSource.drainAndVerify()
            }
        } finally {
            contentSource.close()
        }
        if (validateHashes && headerHash != null && headerHash != rawHeaderData.sha256()) {
            throw FormatError.InvalidHeader("HeaderHash value does not match Sha256 of the header.")
        }
    }
}

private fun visitBinaryContentsVer4x(
    header: DatabaseHeader.Ver4x,
    source: BufferedStream,
    rawHeaderData: ByteString,
    validateHashes: Boolean,
    visitor: KdbxBinaryContentVisitor,
    cipher: CipherProvider,
    transformedKey: ByteArray,
    masterSeed: ByteArray,
    masterKey: ByteArray,
    limits: KdbxReadLimits,
    checkCancellation: () -> Unit,
) {
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

    val blocks = ContentBlocks.ver4Source(
        source = source,
        masterSeed = masterSeed,
        transformedKey = transformedKey,
        maximumBlockSize = limits.maximumBlockSize,
    )
    val decryptor = blocks.closeOnFailure {
        cipher.createDecryptor(masterKey, header.encryptionIV.toByteArray())
    }
    val decrypted = CipherSource(
        upstream = blocks,
        session = decryptor,
    )
    val contentSource = decrypted.decodeCompression(header.compression, limits).buffer()
    try {
        val innerHeader = visitInnerHeaderBinaryContents(
            source = contentSource,
            visitor = visitor,
            checkCancellation = checkCancellation,
        )
        val saltGenerator = EncryptionSaltGenerator.create(
            id = innerHeader.randomStreamId,
            key = innerHeader.randomStreamKey,
        )
        visitXmlBinaryContents(
            source = contentSource,
            innerEncryption = saltGenerator,
            visitor = XmlBinaryContentVisitor(visitor::visit),
            checkCancellation = checkCancellation,
        )
        contentSource.drainAndVerify()
    } finally {
        contentSource.close()
    }
}

private fun visitInnerHeaderBinaryContents(
    source: BufferedSource,
    visitor: KdbxBinaryContentVisitor,
    checkCancellation: () -> Unit,
): DatabaseInnerHeader {
    var randomStreamId: CrsAlgorithm? = null
    var randomStreamKey: ByteString? = null
    while (true) {
        checkCancellation()
        val id = source.readByte().toUByte().toInt()
        val length = source.readIntLe().toLong()
        if (length < 0L) {
            throw FormatError.InvalidContent("Invalid inner header field length: $length.")
        }
        when (id) {
            InnerHeaderFieldId.Terminator -> {
                if (length != 0L) {
                    throw FormatError.InvalidContent(
                        "Invalid inner header terminator length: $length.",
                    )
                }
                break
            }

            InnerHeaderFieldId.StreamId -> {
                if (length != Int.SIZE_BYTES.toLong()) {
                    throw FormatError.InvalidContent(
                        "Invalid inner random stream id field length: $length.",
                    )
                }
                val ordinal = source.readIntLe()
                randomStreamId = CrsAlgorithm.entries
                    .getOrNull(ordinal)
                    ?: throw FormatError.InvalidContent(
                        "Unknown inner random stream id: $ordinal.",
                    )
            }

            InnerHeaderFieldId.StreamKey -> {
                if (length > 1024L * 1024L) {
                    throw FormatError.InvalidContent("Inner random stream key is too large.")
                }
                randomStreamKey = source.readByteString(length)
            }

            InnerHeaderFieldId.Binary -> {
                if (length < 1L) {
                    throw FormatError.InvalidContent(
                        "Invalid binary inner header field length: $length.",
                    )
                }
                source.readByte() // memory-protection flag
                val contentLength = length - 1L
                ExactLengthSource(
                    delegate = source,
                    length = contentLength,
                    checkCancellation = checkCancellation,
                ).use { binarySource ->
                    visitor.visit(
                        source = LimitedSource(
                            delegate = binarySource,
                            maximumBytes = MAX_DECOMPRESSED_SIZE,
                            limitExceeded = {
                                FormatError.InvalidContent(
                                    "Binary content exceeds $MAX_DECOMPRESSED_SIZE bytes.",
                                )
                            },
                        ),
                        declaredLength = contentLength,
                    )
                }
            }

            else -> source.skip(length)
        }
    }
    return DatabaseInnerHeader(
        randomStreamId = randomStreamId
            ?: throw FormatError.InvalidContent("No inner random stream id found."),
        randomStreamKey = randomStreamKey
            ?: throw FormatError.InvalidContent("No inner random stream key found."),
    )
}

private class ExactLengthSource(
    private val delegate: Source,
    length: Long,
    private val checkCancellation: () -> Unit,
) : Source {
    private var remaining = length
    private var closed = false

    init {
        require(length >= 0L) { "length < 0" }
    }

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        checkCancellation()
        check(!closed) { "Exact-length source is closed" }
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L
        if (remaining == 0L) return -1L
        val read = delegate.read(sink, minOf(byteCount, remaining))
        if (read == -1L) {
            throw FormatError.InvalidContent("Binary content ended before its declared length.")
        }
        remaining -= read
        return read
    }

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() {
        if (closed) return
        try {
            val discard = Buffer()
            while (remaining > 0L) {
                checkCancellation()
                val read = delegate.read(
                    discard,
                    minOf(remaining, STREAM_BUFFER_SIZE.toLong()),
                )
                if (read == -1L) {
                    throw FormatError.InvalidContent(
                        "Binary content ended before its declared length.",
                    )
                }
                remaining -= read
                discard.clear()
            }
        } finally {
            closed = true
        }
    }
}

private class CancellationCheckingSource(
    private val delegate: Source,
    private val checkCancellation: () -> Unit,
) : Source {
    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        checkCancellation()
        return delegate.read(sink, byteCount)
    }

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() = delegate.close()
}
