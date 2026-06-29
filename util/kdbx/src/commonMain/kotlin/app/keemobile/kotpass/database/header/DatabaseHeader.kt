package app.keemobile.kotpass.database.header

import app.keemobile.kotpass.constants.CrsAlgorithm
import app.keemobile.kotpass.constants.HeaderFieldId
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.asIntLe
import app.keemobile.kotpass.extensions.asLongLe
import app.keemobile.kotpass.extensions.asUuid
import app.keemobile.kotpass.extensions.nextByteString
import app.keemobile.kotpass.io.BufferedStream
import app.keemobile.kotpass.models.FormatVersion
import okio.BufferedSink
import okio.ByteString
import app.keemobile.kotpass.cryptography.SecureRandom
import kotlin.uuid.Uuid

private val EndOfHeaderBytes = ByteString.of(0x0D, 0x0A, 0x0D, 0x0A)

sealed class DatabaseHeader {
    abstract val signature: Signature
    abstract val version: FormatVersion
    abstract val cipherId: Uuid
    abstract val compression: Compression
    abstract val masterSeed: ByteString
    abstract val encryptionIV: ByteString

    data class Ver3x(
        override val signature: Signature,
        override val version: FormatVersion,
        override val cipherId: Uuid,
        override val compression: Compression,
        override val masterSeed: ByteString,
        override val encryptionIV: ByteString,
        val transformSeed: ByteString,
        val transformRounds: ULong,
        val innerRandomStreamId: CrsAlgorithm,
        val innerRandomStreamKey: ByteString,
        val streamStartBytes: ByteString
    ) : DatabaseHeader() {
        companion object {
            /**
             * Create an instance of [DatabaseHeader] with the default parameters.
             */
            fun create(random: SecureRandom = SecureRandom()) = with(random) {
                Ver3x(
                    signature = Signature.Default,
                    version = FormatVersion(3, 1),
                    cipherId = BaseCiphers.Aes.uuid,
                    compression = Compression.GZip,
                    masterSeed = nextByteString(32),
                    encryptionIV = nextByteString(BaseCiphers.Aes.ivLength.toInt()),
                    transformSeed = nextByteString(32),
                    transformRounds = 6000U,
                    innerRandomStreamId = CrsAlgorithm.Salsa20,
                    innerRandomStreamKey = nextByteString(32),
                    streamStartBytes = nextByteString(32)
                )
            }
        }
    }

    data class Ver4x(
        override val signature: Signature,
        override val version: FormatVersion,
        override val cipherId: Uuid,
        override val compression: Compression,
        override val masterSeed: ByteString,
        override val encryptionIV: ByteString,
        val kdfParameters: KdfParameters,
        val publicCustomData: Map<String, VariantItem>
    ) : DatabaseHeader() {
        companion object {
            /**
             * Create an instance of [DatabaseHeader] with the default parameters.
             */
            fun create(random: SecureRandom = SecureRandom()) = with(random) {
                Ver4x(
                    signature = Signature.Default,
                    version = FormatVersion(4, 1),
                    cipherId = BaseCiphers.Aes.uuid,
                    compression = Compression.GZip,
                    masterSeed = nextByteString(32),
                    encryptionIV = nextByteString(BaseCiphers.Aes.ivLength.toInt()),
                    kdfParameters = KdfParameters.Argon2.default(nextByteString(32)),
                    publicCustomData = mapOf()
                )
            }
        }
    }

    enum class Compression {
        None,
        GZip
    }

    internal fun writeTo(sink: BufferedSink) {
        signature.writeTo(sink)
        version.writeTo(sink)

        writeHeaderValue(sink, HeaderFieldId.CipherId, 16) {
            write(cipherId.toByteArray())
        }
        writeHeaderValue(sink, HeaderFieldId.Compression, Int.SIZE_BYTES) {
            writeIntLe(compression.ordinal)
        }
        writeHeaderValue(sink, HeaderFieldId.MasterSeed, masterSeed.size) {
            write(masterSeed)
        }
        writeHeaderValue(sink, HeaderFieldId.EncryptionIV, encryptionIV.size) {
            write(encryptionIV)
        }

        when (this) {
            is Ver3x -> {
                writeHeaderValue(sink, HeaderFieldId.TransformSeed, transformSeed.size) {
                    write(transformSeed)
                }
                writeHeaderValue(sink, HeaderFieldId.TransformRounds, Long.SIZE_BYTES) {
                    writeLongLe(transformRounds.toLong())
                }
                writeHeaderValue(sink, HeaderFieldId.InnerRandomStreamId, Int.SIZE_BYTES) {
                    writeIntLe(innerRandomStreamId.ordinal)
                }
                writeHeaderValue(
                    sink,
                    HeaderFieldId.InnerRandomStreamKey,
                    innerRandomStreamKey.size
                ) {
                    write(innerRandomStreamKey)
                }
                writeHeaderValue(sink, HeaderFieldId.StreamStartBytes, streamStartBytes.size) {
                    write(streamStartBytes)
                }
            }
            is Ver4x -> {
                val params = kdfParameters.writeToByteString()
                writeHeaderValue(sink, HeaderFieldId.KdfParameters, params.size) {
                    write(params)
                }
                val customData = VariantDictionary.writeToByteString(publicCustomData)
                writeHeaderValue(sink, HeaderFieldId.PublicCustomData, customData.size) {
                    write(customData)
                }
            }
        }

        writeHeaderValue(sink, HeaderFieldId.EndOfHeader, EndOfHeaderBytes.size) {
            write(EndOfHeaderBytes)
        }
    }

    private inline fun writeHeaderValue(
        sink: BufferedSink,
        id: HeaderFieldId,
        length: Int,
        block: BufferedSink.() -> Unit
    ) {
        sink.writeByte(id.ordinal)
        if (this is Ver4x) {
            sink.writeIntLe(length)
        } else {
            sink.writeShortLe(length)
        }
        block(sink)
    }

    companion object {
        internal fun readFrom(source: BufferedStream): DatabaseHeader {
            var cipherId: Uuid? = null
            var compression: Compression? = null
            var masterSeed: ByteString? = null
            var transformSeed: ByteString? = null
            var transformRounds: ULong? = null
            var encryptionIV: ByteString? = null
            var innerRandomStreamKey: ByteString? = null
            var streamStartBytes: ByteString? = null
            var innerRandomStreamID: CrsAlgorithm? = null
            var kdfParameters: KdfParameters? = null
            var publicCustomData: Map<String, VariantItem> = mapOf()

            val signature = Signature.readFrom(source)
            val version = FormatVersion.readFrom(source)

            while (true) {
                val (id, data) = readHeaderValue(source, version)
                val fieldId = HeaderFieldId
                    .entries
                    .getOrNull(id)
                    ?: throw FormatError.InvalidHeader("Unsupported header field ID.")

                when (fieldId) {
                    HeaderFieldId.EndOfHeader -> break
                    HeaderFieldId.Comment -> Unit
                    HeaderFieldId.CipherId -> cipherId = data.asUuid()
                    HeaderFieldId.Compression -> {
                        compression = Compression.entries[data.asIntLe()]
                    }
                    HeaderFieldId.MasterSeed -> masterSeed = data
                    HeaderFieldId.TransformSeed -> transformSeed = data
                    HeaderFieldId.TransformRounds -> transformRounds = data.asLongLe().toULong()
                    HeaderFieldId.EncryptionIV -> encryptionIV = data
                    HeaderFieldId.InnerRandomStreamKey -> innerRandomStreamKey = data
                    HeaderFieldId.StreamStartBytes -> streamStartBytes = data
                    HeaderFieldId.InnerRandomStreamId -> {
                        innerRandomStreamID = CrsAlgorithm.entries[data.asIntLe()]
                    }
                    HeaderFieldId.KdfParameters -> {
                        kdfParameters = KdfParameters.readFrom(data)
                    }
                    HeaderFieldId.PublicCustomData -> {
                        publicCustomData = VariantDictionary.readFrom(data)
                    }
                }
            }

            return if (version.major < 4) {
                Ver3x(
                    signature = signature,
                    version = version,
                    cipherId = cipherId
                        ?: throw FormatError.InvalidHeader("No cipher ID."),
                    compression = compression
                        ?: throw FormatError.InvalidHeader("No compression."),
                    masterSeed = masterSeed
                        ?: throw FormatError.InvalidHeader("No master seed."),
                    encryptionIV = encryptionIV
                        ?: throw FormatError.InvalidHeader("No encryption IV."),
                    transformSeed = transformSeed
                        ?: throw FormatError.InvalidHeader("No transform seed."),
                    transformRounds = transformRounds
                        ?: throw FormatError.InvalidHeader("No transform rounds."),
                    innerRandomStreamId = innerRandomStreamID
                        ?: throw FormatError.InvalidHeader("No inner random stream ID."),
                    innerRandomStreamKey = innerRandomStreamKey
                        ?: throw FormatError.InvalidHeader("No protected stream key."),
                    streamStartBytes = streamStartBytes
                        ?: throw FormatError.InvalidHeader("No stream start bytes.")
                )
            } else {
                Ver4x(
                    signature = signature,
                    version = version,
                    cipherId = cipherId
                        ?: throw FormatError.InvalidHeader("No cipher ID."),
                    compression = compression
                        ?: throw FormatError.InvalidHeader("No compression."),
                    masterSeed = masterSeed
                        ?: throw FormatError.InvalidHeader("No master seed."),
                    encryptionIV = encryptionIV
                        ?: throw FormatError.InvalidHeader("No encryption IV."),
                    kdfParameters = kdfParameters
                        ?: throw FormatError.InvalidHeader("No kdf parameters found."),
                    publicCustomData = publicCustomData
                )
            }
        }

        private fun readHeaderValue(
            source: BufferedStream,
            version: FormatVersion
        ): Pair<Int, ByteString> {
            val id = source.readByte()
            val length = if (version.major >= 4) {
                source.readIntLe().toLong()
            } else {
                source.readShortLe().toLong()
            }
            val data = if (length > 0) {
                source.readByteString(length)
            } else {
                ByteString.EMPTY
            }
            return id.toInt() to data
        }
    }
}
