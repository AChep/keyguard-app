package app.keemobile.kotpass.database.header

import app.keemobile.kotpass.constants.VariantTypeId
import app.keemobile.kotpass.errors.FormatError
import okio.Buffer
import okio.ByteString

internal object VariantDictionary {
    private const val Version = 0x0100
    private const val VersionFilter = 0xFF00

    fun readFrom(data: ByteString): Map<String, VariantItem> {
        val result = mutableMapOf<String, VariantItem>()
        val buffer = Buffer().write(data)
        val version = buffer.readShortLe().toUShort().toInt()

        if ((version and VersionFilter) > (Version and VersionFilter)) {
            throw FormatError.InvalidHeader("Variant dictionary version exceeds expected value.")
        }

        while (true) {
            val type = buffer.readByte().toUByte().toInt()

            if (type != VariantTypeId.None) {
                val keyLength: Int = buffer.readIntLe()
                if (keyLength <= 0) {
                    throw FormatError.InvalidHeader("Variant dictionary item's key has zero length.")
                }
                val key: String = buffer
                    .readByteString(keyLength.toLong())
                    .utf8()
                val valueLength: Int = buffer.readIntLe()
                if (valueLength < 0) {
                    throw FormatError.InvalidHeader("Variant dictionary item's value has invalid length.")
                }
                val value = Buffer().write(buffer.readByteString(valueLength.toLong()))

                when (type) {
                    VariantTypeId.UInt32 -> {
                        if (valueLength != UInt.SIZE_BYTES) {
                            throw FormatError.InvalidHeader("Invalid item's value length for type: UInt32.")
                        }
                        result[key] = VariantItem.UInt32(value.readIntLe().toUInt())
                    }
                    VariantTypeId.UInt64 -> {
                        if (valueLength != ULong.SIZE_BYTES) {
                            throw FormatError.InvalidHeader("Invalid item's value length for type: UInt64.")
                        }
                        result[key] = VariantItem.UInt64(value.readLongLe().toULong())
                    }
                    VariantTypeId.Bool -> {
                        if (valueLength != Byte.SIZE_BYTES) {
                            throw FormatError.InvalidHeader("Invalid item's value length for type: Bool.")
                        }
                        result[key] = VariantItem.Bool(value.readByte() != 0x0.toByte())
                    }
                    VariantTypeId.Int32 -> {
                        if (valueLength != Int.SIZE_BYTES) {
                            throw FormatError.InvalidHeader("Invalid item's value length for type: Int32.")
                        }
                        result[key] = VariantItem.Int32(value.readIntLe())
                    }
                    VariantTypeId.Int64 -> {
                        if (valueLength != Long.SIZE_BYTES) {
                            throw FormatError.InvalidHeader("Invalid item's value length for type: Int64.")
                        }
                        result[key] = VariantItem.Int64(value.readLongLe())
                    }
                    VariantTypeId.StringUtf8 -> {
                        result[key] = VariantItem.StringUtf8(
                            value.readUtf8()
                        )
                    }
                    VariantTypeId.Bytes -> {
                        result[key] = VariantItem.Bytes(
                            value.readByteString()
                        )
                    }
                    else -> throw FormatError.InvalidHeader(
                        "Unsupported variant dictionary item type: $type."
                    )
                }
            } else {
                break
            }
        }

        return result
    }

    fun writeToByteString(items: Map<String, VariantItem>) = with(Buffer()) {
        writeShortLe(Version)

        for ((key, item) in items) {
            writeByte(item.typeId)
            writeIntLe(key.encodeToByteArray().size)
            writeUtf8(key)

            when (item) {
                is VariantItem.UInt32 -> {
                    writeIntLe(Int.SIZE_BYTES)
                    writeIntLe(item.value.toInt())
                }
                is VariantItem.UInt64 -> {
                    writeIntLe(Long.SIZE_BYTES)
                    writeLongLe(item.value.toLong())
                }
                is VariantItem.Bool -> {
                    writeIntLe(Byte.SIZE_BYTES)
                    writeByte(if (item.value) 0x1 else 0x0)
                }
                is VariantItem.Int32 -> {
                    writeIntLe(Int.SIZE_BYTES)
                    writeIntLe(item.value)
                }
                is VariantItem.Int64 -> {
                    writeIntLe(Long.SIZE_BYTES)
                    writeLongLe(item.value)
                }
                is VariantItem.StringUtf8 -> {
                    writeIntLe(item.value.encodeToByteArray().size)
                    writeUtf8(item.value)
                }
                is VariantItem.Bytes -> {
                    writeIntLe(item.value.size)
                    write(item.value)
                }
            }
        }
        writeByte(VariantTypeId.None)

        snapshot()
    }
}
