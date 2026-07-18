package app.keemobile.kotpass.models

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.constants.Defaults
import app.keemobile.kotpass.constants.MemoryProtectionFlag
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.database.BinaryPool
import app.keemobile.kotpass.database.BinaryIndex
import app.keemobile.kotpass.database.BinaryWritePlan
import app.keemobile.kotpass.errors.FormatError
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
         * Defines the exact binary order and references used by this encoding.
         */
        internal abstract val binaryWritePlan: BinaryWritePlan

        /**
         * Binaries in the normalized order used by this encoding.
         */
        val binaries: Map<ByteString, BinaryData>
            get() = binaryWritePlan.binaries

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
        class Encrypted internal constructor(
            override val version: FormatVersion,
            val innerEncryption: EncryptionSaltGenerator,
            internal override val binaryWritePlan: BinaryWritePlan,
        ) : Encode() {
            constructor(
                version: FormatVersion,
                binaries: Map<ByteString, BinaryData>,
                innerEncryption: EncryptionSaltGenerator,
            ) : this(
                version = version,
                innerEncryption = innerEncryption,
                binaryWritePlan = BinaryWritePlan.create(binaries),
            )
        }

        /**
         * Used when XML file is supposed to be saved as plain text.
         *
         * This mode affects how fields are processed:
         * * `protected` fields are saved unencrypted with `ProtectInMemory` attribute.
         * * timestamps are encoded as ISO-8601 instant text.
         * * binaries are stored as `BASE64(u8..)` in [Meta].
         */
        class Plain internal constructor(
            override val version: FormatVersion,
            val memoryProtectionFlags: Set<MemoryProtectionFlag>,
            internal override val binaryWritePlan: BinaryWritePlan,
        ) : Encode() {
            constructor(
                version: FormatVersion,
                binaries: Map<ByteString, BinaryData>,
                memoryProtectionFlags: Set<MemoryProtectionFlag>,
            ) : this(
                version = version,
                memoryProtectionFlags = memoryProtectionFlags,
                binaryWritePlan = BinaryWritePlan.create(binaries),
            )

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

        internal fun addBinary(binary: BinaryData): ByteString = when (binaries) {
            is BinaryPool -> binaries.add(binary)
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val mutableBinaries = binaries as MutableMap<ByteString, BinaryData>
                if (binary.hash !in mutableBinaries) {
                    mutableBinaries[binary.hash] = binary
                }
                binary.hash
            }
            else -> throw FormatError.InvalidContent("Binary pool is not mutable.")
        }
    }
}
