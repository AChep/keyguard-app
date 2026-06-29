package app.keemobile.kotpass.database.header

import app.keemobile.kotpass.constants.VariantTypeId
import okio.ByteString
import kotlin.jvm.JvmInline

/**
 * Represents a variant data type item within KDBX file header.
 */
sealed interface VariantItem {
    /**
     * The unique identifier for the variant's data type,
     * corresponding to [VariantTypeId].
     */
    val typeId: Int

    /** Represents an unsigned 32-bit integer variant item. */
    @JvmInline
    value class UInt32(val value: UInt) : VariantItem {
        override val typeId: Int
            get() = VariantTypeId.UInt32
    }

    /** Represents an unsigned 64-bit integer variant item. */
    @JvmInline
    value class UInt64(val value: ULong) : VariantItem {
        override val typeId: Int
            get() = VariantTypeId.UInt64
    }

    /** Represents a boolean variant item. */
    @JvmInline
    value class Bool(val value: Boolean) : VariantItem {
        override val typeId: Int
            get() = VariantTypeId.Bool
    }

    /** Represents a signed 32-bit integer variant item. */
    @JvmInline
    value class Int32(val value: Int) : VariantItem {
        override val typeId: Int
            get() = VariantTypeId.Int32
    }

    /** Represents a signed 64-bit integer variant item. */
    @JvmInline
    value class Int64(val value: Long) : VariantItem {
        override val typeId: Int
            get() = VariantTypeId.Int64
    }

    /** Represents a UTF-8 encoded string variant item. */
    @JvmInline
    value class StringUtf8(val value: String) : VariantItem {
        override val typeId: Int
            get() = VariantTypeId.StringUtf8
    }

    /** Represents a byte array variant item. */
    @JvmInline
    value class Bytes(val value: ByteString) : VariantItem {
        override val typeId: Int
            get() = VariantTypeId.Bytes
    }
}
