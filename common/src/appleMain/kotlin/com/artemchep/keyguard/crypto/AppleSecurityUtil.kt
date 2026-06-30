package com.artemchep.keyguard.crypto

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal inline fun <T> ByteArray.usePinnedOrNull(
    block: (CPointer<ByteVar>?) -> T,
): T {
    if (isEmpty()) {
        return block(null)
    }
    return usePinned { pinned ->
        block(pinned.addressOf(0))
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun CFDataRef.toByteArray(): ByteArray {
    val size = CFDataGetLength(this).toInt()
    if (size == 0) {
        return ByteArray(0)
    }
    val bytes = checkNotNull(CFDataGetBytePtr(this)) {
        "CFData bytes pointer is null."
    }
    return ByteArray(size).also { output ->
        output.usePinned { outputPinned ->
            memcpy(
                outputPinned.addressOf(0),
                bytes,
                size.convert(),
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal inline fun <T> ByteArray.useCFData(
    block: (CFDataRef) -> T,
): T {
    val data = usePinnedOrNull { ptr ->
        CFDataCreate(
            allocator = null,
            bytes = ptr?.reinterpret(),
            length = size.convert(),
        )
    }
    checkNotNull(data) {
        "Could not allocate CFData."
    }
    return try {
        block(data)
    } finally {
        CFRelease(data)
    }
}

/**
 * Minimal DER reader for parsing the ASN.1 structures emitted by
 * `SecKeyCopyExternalRepresentation` (PKCS#1 `RSAPublicKey` / `RSAPrivateKey`)
 * and PKCS#8 wrappers.
 */
internal class DerReader(
    private val data: ByteArray,
) {
    private var offset = 0

    fun peekTag(): Int? = data.getOrNull(offset)?.toInt()?.and(0xff)

    fun readConstructed(
        expectedTag: Int,
    ): DerReader {
        val value = readValue(expectedTag)
        return DerReader(value)
    }

    fun readIntegerBytes(): ByteArray = readValue(0x02)

    fun readOctetString(): ByteArray = readValue(0x04)

    fun readAny(): ByteArray {
        readByte()
        val length = readLength()
        return readBytes(length)
    }

    private fun readValue(
        expectedTag: Int,
    ): ByteArray {
        val tag = readByte()
        require(tag == expectedTag) {
            "Unexpected DER tag $tag, expected $expectedTag."
        }
        val length = readLength()
        return readBytes(length)
    }

    private fun readLength(): Int {
        val first = readByte()
        if (first and 0x80 == 0) {
            return first
        }
        val count = first and 0x7f
        require(count in 1..4) {
            "Unsupported DER length."
        }
        var length = 0
        repeat(count) {
            length = (length shl 8) or readByte()
        }
        return length
    }

    private fun readByte(): Int {
        require(offset < data.size) {
            "Unexpected end of DER data."
        }
        return data[offset++].toInt() and 0xff
    }

    private fun readBytes(
        length: Int,
    ): ByteArray {
        require(length >= 0 && offset + length <= data.size) {
            "Invalid DER length."
        }
        return data.copyOfRange(offset, offset + length)
            .also {
                offset += length
            }
    }
}
