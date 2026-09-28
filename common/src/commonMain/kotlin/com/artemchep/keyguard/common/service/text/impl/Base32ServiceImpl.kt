package com.artemchep.keyguard.common.service.text.impl

import com.artemchep.keyguard.common.service.text.Base32Service

class Base32ServiceImpl : Base32Service {
    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }

    override fun encode(bytes: ByteArray): ByteArray {
        val output = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        bytes.forEach { byte ->
            buffer = (buffer shl Byte.SIZE_BITS) or (byte.toInt() and BYTE_MASK)
            bitsLeft += Byte.SIZE_BITS
            while (bitsLeft >= BASE32_SYMBOL_BITS) {
                output.append(ALPHABET[(buffer shr (bitsLeft - BASE32_SYMBOL_BITS)) and 0x1f])
                bitsLeft -= BASE32_SYMBOL_BITS
            }
            buffer = if (bitsLeft > 0) buffer and ((1 shl bitsLeft) - 1) else 0
        }
        if (bitsLeft > 0) {
            output.append(ALPHABET[(buffer shl (BASE32_SYMBOL_BITS - bitsLeft)) and 0x1f])
        }
        while (output.length % BASE32_BLOCK_LENGTH != 0) {
            output.append('=')
        }
        return output.toString().encodeToByteArray()
    }

    override fun decode(bytes: ByteArray): ByteArray {
        val output = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        bytes.decodeToString()
            .uppercase()
            .asSequence()
            .filterNot { it == '=' || it.isWhitespace() || it == '-' }
            .forEach { char ->
                val value = ALPHABET.indexOf(char)
                require(value >= 0) {
                    "Invalid Base32 character: $char"
                }
                buffer = (buffer shl BASE32_SYMBOL_BITS) or value
                bitsLeft += BASE32_SYMBOL_BITS
                if (bitsLeft >= Byte.SIZE_BITS) {
                    bitsLeft -= Byte.SIZE_BITS
                    output += ((buffer shr bitsLeft) and BYTE_MASK).toByte()
                    buffer = if (bitsLeft > 0) buffer and ((1 shl bitsLeft) - 1) else 0
                }
            }
        return output.toByteArray()
    }
}

private const val BYTE_MASK = 0xff

private const val BASE32_SYMBOL_BITS = 5

private const val BASE32_BLOCK_LENGTH = 8
