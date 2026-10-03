package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf

// The one-shot protobuf AES-CBC-HMAC operations are not used by the app; the
// fast path and the streaming sessions are. These helpers keep the protobuf
// path reachable for parity tests and layer benchmarks.

private const val ENCRYPT_OPERATION = "aes_cbc_pkcs7_hmac_sha256_encrypt"
private const val DECRYPT_OPERATION = "aes_cbc_pkcs7_hmac_sha256_decrypt"

internal fun NativeCryptoPrimitives.aesCbcPkcs7HmacSha256EncryptViaProtobuf(
    encryptionKey: ByteArray,
    macKey: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
): NativeAesCbcHmacSha256Result {
    val encodedResult = NativeCrypto.call(
        operationName = ENCRYPT_OPERATION,
        operation = AesCbcPkcs7HmacSha256EncryptOperationProto(
            AesCbcPkcs7HmacSha256EncryptRequestProto(
                encryptionKey = encryptionKey,
                macKey = macKey,
                iv = iv,
                plaintext = plaintext,
            ),
        ),
    ).requireBytes(ENCRYPT_OPERATION)
    return try {
        val result = ProtoBuf.decodeFromByteArray<AesCbcPkcs7HmacSha256EncryptResultProto>(
            encodedResult,
        )
        NativeAesCbcHmacSha256Result(
            ciphertext = result.ciphertext,
            mac = result.mac,
        )
    } finally {
        encodedResult.fill(0)
    }
}

internal fun NativeCryptoPrimitives.aesCbcPkcs7HmacSha256DecryptViaProtobuf(
    encryptionKey: ByteArray,
    macKey: ByteArray,
    iv: ByteArray,
    ciphertext: ByteArray,
    expectedMac: ByteArray,
): ByteArray = NativeCrypto.call(
    operationName = DECRYPT_OPERATION,
    operation = AesCbcPkcs7HmacSha256DecryptOperationProto(
        AesCbcPkcs7HmacSha256DecryptRequestProto(
            encryptionKey = encryptionKey,
            macKey = macKey,
            iv = iv,
            ciphertext = ciphertext,
            expectedMac = expectedMac,
        ),
    ),
).requireBytes(DECRYPT_OPERATION)
