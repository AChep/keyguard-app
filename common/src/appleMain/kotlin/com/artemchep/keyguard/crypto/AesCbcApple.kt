package com.artemchep.keyguard.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCBlockSizeAES128
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess

@OptIn(ExperimentalForeignApi::class)
internal fun aesCbcPkcs7(
    data: ByteArray,
    iv: ByteArray,
    key: ByteArray,
    operation: UInt,
): ByteArray = memScoped {
    val blockSize = kCCBlockSizeAES128.toInt()
    require(iv.size == blockSize) {
        "AES-CBC requires a ${kCCBlockSizeAES128}-byte initialization vector."
    }
    require(key.size == 16 || key.size == 32) {
        "AES-CBC requires a 16-byte or 32-byte key."
    }

    val output = ByteArray(data.size + blockSize)
    val outputMoved = alloc<ULongVar>()
    val status = key.usePinned { keyPinned ->
        iv.usePinned { ivPinned ->
            data.usePinnedOrNull { dataPtr ->
                output.usePinned { outputPinned ->
                    CCCrypt(
                        op = operation,
                        alg = kCCAlgorithmAES,
                        options = kCCOptionPKCS7Padding,
                        key = keyPinned.addressOf(0),
                        keyLength = key.size.convert(),
                        iv = ivPinned.addressOf(0),
                        dataIn = dataPtr,
                        dataInLength = data.size.convert(),
                        dataOut = outputPinned.addressOf(0),
                        dataOutAvailable = output.size.convert(),
                        dataOutMoved = outputMoved.ptr,
                    )
                }
            }
        }
    }
    check(status == kCCSuccess) {
        "AES-CBC operation failed with status $status."
    }
    output.copyOf(outputMoved.value.toInt())
}
