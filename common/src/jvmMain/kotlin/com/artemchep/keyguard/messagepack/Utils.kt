package com.artemchep.keyguard.messagepack

import java.io.IOException
import java.lang.reflect.Array
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.nio.ByteBuffer

object Utils {
    @Throws(IOException::class)
    fun readLengthHeader(buffer: ByteBuffer): Int {
        // The payload starts with a length prefix encoded as a VarInt. VarInts use the most significant bit
        // as a marker whether the byte is the last byte of the VarInt or if it spans to the next byte. Bytes
        // appear in the reverse order - i.e. the first byte contains the least significant bits of the value
        // Examples:
        // VarInt: 0x35 - %00110101 - the most significant bit is 0 so the value is %x0110101 i.e. 0x35 (53)
        // VarInt: 0x80 0x25 - %10000000 %00101001 - the most significant bit of the first byte is 1 so the
        // remaining bits (%x0000000) are the lowest bits of the value. The most significant bit of the second
        // byte is 0 meaning this is last byte of the VarInt. The actual value bits (%x0101001) need to be
        // prepended to the bits we already read so the values is %01010010000000 i.e. 0x1480 (5248)
        // We support payloads up to 2GB so the biggest number we support is 7fffffff which when encoded as
        // VarInt is 0xFF 0xFF 0xFF 0xFF 0x07 - hence the maximum length prefix is 5 bytes.
        var length = 0
        var numBytes = 0
        val maxLength = 5
        var curr: Byte
        do {
            // If we run out of bytes before we finish reading the length header, the message is malformed
            curr = if (buffer.hasRemaining()) {
                buffer.get()
            } else {
                throw RuntimeException("The length header was incomplete")
            }
            length = length or (curr.toInt() and 0x7f.toByte().toInt() shl numBytes * 7)
            numBytes++
        } while (numBytes < maxLength && curr.toInt() and 0x80.toByte().toInt() != 0)

        // Max header length is 5, and the maximum value of the 5th byte is 0x07
        if (curr.toInt() and 0x80.toByte()
                .toInt() != 0 || numBytes == maxLength && curr > 0x07.toByte()
        ) {
            throw RuntimeException("Messages over 2GB in size are not supported")
        }
        return length
    }

    fun getLengthHeader(length: Int): ArrayList<Byte> {
        // This code writes length prefix of the message as a VarInt. Read the comment in
        // the readLengthHeader for details.
        var length = length
        val header = ArrayList<Byte>()
        do {
            var curr = (length and 0x7f).toByte()
            length = length shr 7
            if (length > 0) {
                curr = (curr.toInt() or 0x80).toByte()
            }
            header.add(curr)
        } while (length > 0)
        return header
    }

    fun toPrimitive(c: Class<*>, value: Any?): Any? {
        if (Boolean::class.javaPrimitiveType == c) return value as Boolean?
        if (Byte::class.javaPrimitiveType == c) return value as Byte?
        if (Short::class.javaPrimitiveType == c) return value as Short?
        if (Int::class.javaPrimitiveType == c) return value as Int?
        if (Long::class.javaPrimitiveType == c) return value as Long?
        if (Float::class.javaPrimitiveType == c) return value as Float?
        if (Double::class.javaPrimitiveType == c) return value as Double?
        return if (Char::class.javaPrimitiveType == c) value as Char? else value
    }

    fun typeToClass(type: Type?): Class<*>? {
        if (type == null) {
            return null
        }
        return if (type is Class<*>) {
            type
        } else if (type is GenericArrayType) {
            // Instantiate an array of the same type as this type, then return its class
            Array.newInstance(
                typeToClass(
                    type.genericComponentType,
                )!!,
                0,
            ).javaClass
        } else if (type is ParameterizedType) {
            typeToClass(type.rawType)
        } else if (type is TypeVariable<*>) {
            val bounds = type.bounds
            if (bounds.isEmpty()) {
                Any::class.java
            } else {
                typeToClass(
                    bounds[0],
                )
            }
        } else if (type is WildcardType) {
            val bounds = type.upperBounds
            if (bounds.isEmpty()) {
                Any::class.java
            } else {
                typeToClass(
                    bounds[0],
                )
            }
        } else {
            throw UnsupportedOperationException("Cannot handle type class: " + type.javaClass)
        }
    }
}
