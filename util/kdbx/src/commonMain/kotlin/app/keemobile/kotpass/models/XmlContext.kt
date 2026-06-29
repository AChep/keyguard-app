package app.keemobile.kotpass.models

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.constants.Defaults
import app.keemobile.kotpass.constants.MemoryProtectionFlag
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.database.BinaryIndex
import okio.ByteString

/**
 * Provides shared configuration and state across encoding/decoding process.
 */
sealed class XmlContext {
    /**
     * Defines the format version, which affects the XML structure.
     */
    abstract val version: FormatVersion

    /**
     * XML parser context used during encoding.
     */
    sealed class Encode : XmlContext() {
        /**
         * Supports encoding references to binary data.
         */
        abstract val binaries: Map<ByteString, BinaryData>

        val binaryIndex: BinaryIndex by lazy {
            BinaryIndex(binaries)
        }

        /**
         * Used when XML file is supposed to be encrypted in binary KDBX format.
         *
         * This mode affects how fields are processed:
         * * `protected` fields are additionally encrypted using [innerEncryption].
         * * timestamps are encoded as `BASE64(i64)` when [version] is `4.x`.
         */
        class Encrypted(
            override val version: FormatVersion,
            override val binaries: Map<ByteString, BinaryData>,
            val innerEncryption: EncryptionSaltGenerator
        ) : Encode()

        /**
         * Used when XML file is supposed to be saved as plain text.
         *
         * This mode affects how fields are processed:
         * * `protected` fields are saved unencrypted with `ProtectInMemory` attribute.
         * * timestamps are encoded as ISO-8601 instant text.
         * * binaries are stored as `BASE64(u8..)` in [Meta].
         */
        class Plain(
            override val version: FormatVersion,
            override val binaries: Map<ByteString, BinaryData>,
            val memoryProtectionFlags: Set<MemoryProtectionFlag>
        ) : Encode() {
            val memoryProtectionKeys = memoryProtectionFlags
                .map(MemoryProtectionFlag::toBasicField)
                .map(BasicField::key)
                .toSet()
        }
    }

    /**
     * XML parser context used during decoding.
     */
    class Decode(
        override val version: FormatVersion,
        val encryption: EncryptionSaltGenerator,
        val binaries: Map<ByteString, BinaryData>,
        val untitledLabel: String = Defaults.UntitledLabel
    ) : XmlContext() {
        val binaryIndex: BinaryIndex by lazy {
            BinaryIndex(binaries)
        }
    }
}
