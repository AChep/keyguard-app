package com.artemchep.keyguard.common.service.licensekey

import com.artemchep.keyguard.common.service.licensekey.decoder.Kg2LicenseSignatureVerifier
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyIsAlgorithmSupported
import platform.Security.SecKeyRef
import platform.Security.SecKeyVerifySignature
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecKeyOperationTypeVerify
import kotlin.io.encoding.Base64

@OptIn(ExperimentalForeignApi::class)
class EcdsaP256Kg2LicenseSignatureVerifier : Kg2LicenseSignatureVerifier,
    LicenseEntitlementProofSignatureVerifier {
    override fun verify(
        publicKeyPem: String,
        signingInput: ByteArray,
        signature: ByteArray,
    ): Boolean = runCatching {
        if (signature.size != 64) {
            return@runCatching false
        }

        val publicKey = createPublicSecKey(publicKeyPem)
        try {
            val algorithm = checkNotNull(kSecKeyAlgorithmECDSASignatureMessageX962SHA256)
            if (!SecKeyIsAlgorithmSupported(publicKey, kSecKeyOperationTypeVerify, algorithm)) {
                return@runCatching false
            }

            memScoped {
                val error = alloc<CFErrorRefVar>()
                signingInput.useCFData { messageData ->
                    signatureP1363ToDer(signature).useCFData { signatureData ->
                        SecKeyVerifySignature(
                            key = publicKey,
                            algorithm = algorithm,
                            signedData = messageData,
                            signature = signatureData,
                            error = error.ptr,
                        )
                    }
                }
            }
        } finally {
            CFRelease(publicKey)
        }
    }.getOrDefault(false)

    private fun createPublicSecKey(
        publicKeyPem: String,
    ): SecKeyRef = memScoped {
        val attrs = CFDictionaryCreateMutable(
            allocator = null,
            capacity = 0,
            keyCallBacks = null,
            valueCallBacks = null,
        )
        checkNotNull(attrs) {
            "Could not allocate Security.framework key attributes."
        }
        try {
            CFDictionarySetValue(attrs, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
            CFDictionarySetValue(attrs, kSecAttrKeyClass, kSecAttrKeyClassPublic)

            val error = alloc<CFErrorRefVar>()
            decodePublicKeyPem(publicKeyPem).useCFData { keyData ->
                SecKeyCreateWithData(
                    keyData = keyData,
                    attributes = attrs,
                    error = error.ptr,
                )
            } ?: error("Could not import EC public key.")
        } finally {
            CFRelease(attrs)
        }
    }
}

private fun decodePublicKeyPem(publicKeyPem: String): ByteArray = publicKeyPem
    .lineSequence()
    .filterNot { it.startsWith("-----") }
    .filter { it.isNotBlank() }
    .joinToString(separator = "")
    .let(Base64.Default::decode)

private fun signatureP1363ToDer(signature: ByteArray): ByteArray {
    val r = derInteger(signature.copyOfRange(0, 32))
    val s = derInteger(signature.copyOfRange(32, 64))
    val length = r.size + s.size
    require(length < 128)
    return byteArrayOf(0x30, length.toByte()) + r + s
}

private fun derInteger(value: ByteArray): ByteArray {
    val stripped = value.dropWhile { it == 0.toByte() }
        .ifEmpty { listOf(0.toByte()) }
        .toByteArray()
    val positive = if ((stripped.first().toInt() and 0x80) != 0) {
        byteArrayOf(0) + stripped
    } else {
        stripped
    }
    require(positive.size < 128)
    return byteArrayOf(0x02, positive.size.toByte()) + positive
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> ByteArray.useCFData(
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

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> ByteArray.usePinnedOrNull(
    block: (CPointer<ByteVar>?) -> T,
): T {
    if (isEmpty()) {
        return block(null)
    }
    return usePinned { pinned ->
        block(pinned.addressOf(0))
    }
}
