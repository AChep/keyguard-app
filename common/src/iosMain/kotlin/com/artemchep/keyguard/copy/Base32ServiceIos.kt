package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.text.Base32Service

object Base32ServiceIos : Base32Service {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    override fun encode(bytes: ByteArray): ByteArray {
        val output = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                output.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1f])
                bitsLeft -= 5
            }
            buffer = if (bitsLeft > 0) buffer and ((1 shl bitsLeft) - 1) else 0
        }
        if (bitsLeft > 0) {
            output.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1f])
        }
        while (output.length % 8 != 0) {
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
                buffer = (buffer shl 5) or value
                bitsLeft += 5
                if (bitsLeft >= 8) {
                    bitsLeft -= 8
                    output += ((buffer shr bitsLeft) and 0xff).toByte()
                    buffer = if (bitsLeft > 0) buffer and ((1 shl bitsLeft) - 1) else 0
                }
            }
        return output.toByteArray()
    }
}
