@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoOneOf
import kotlinx.serialization.protobuf.ProtoPacked
import kotlinx.serialization.protobuf.ProtoType

@Serializable
internal data class NativeRequestProto(
    @ProtoNumber(1)
    val protocolVersion: Int,
    @ProtoOneOf
    val operation: NativeRequestOperationProto,
)

@Serializable
internal sealed interface NativeRequestOperationProto

@Serializable
@SerialName("hkdf_sha256")
internal data class HkdfSha256OperationProto(
    @ProtoNumber(10)
    val value: HkdfSha256RequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("pbkdf2_sha256")
internal data class Pbkdf2Sha256OperationProto(
    @ProtoNumber(11)
    val value: Pbkdf2Sha256RequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("argon2")
internal data class Argon2OperationProto(
    @ProtoNumber(12)
    val value: Argon2RequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("random_bytes")
internal data class RandomBytesOperationProto(
    @ProtoNumber(13)
    val value: RandomBytesRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("random_int")
internal data class RandomIntOperationProto(
    @ProtoNumber(14)
    val value: RandomIntRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("random_ints")
internal data class RandomIntsOperationProto(
    @ProtoNumber(19)
    val value: RandomIntsRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("hmac")
internal data class HmacOperationProto(
    @ProtoNumber(15)
    val value: HmacRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("digest")
internal data class DigestOperationProto(
    @ProtoNumber(16)
    val value: DigestRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("aes_ecb_no_padding_encrypt")
internal data class AesEcbNoPaddingEncryptOperationProto(
    @ProtoNumber(17)
    val value: AesEcbNoPaddingEncryptRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("aes_cbc_pkcs7")
internal data class AesCbcPkcs7OperationProto(
    @ProtoNumber(18)
    val value: AesCbcPkcs7RequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("aes_cbc_pkcs7_hmac_sha256_encrypt")
internal data class AesCbcPkcs7HmacSha256EncryptOperationProto(
    @ProtoNumber(45)
    val value: AesCbcPkcs7HmacSha256EncryptRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("aes_cbc_pkcs7_hmac_sha256_decrypt")
internal data class AesCbcPkcs7HmacSha256DecryptOperationProto(
    @ProtoNumber(46)
    val value: AesCbcPkcs7HmacSha256DecryptRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("aes_ecb_no_padding_transform")
internal data class AesEcbNoPaddingTransformOperationProto(
    @ProtoNumber(20)
    val value: AesEcbNoPaddingTransformRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("stream_cipher_xor_at_offset")
internal data class StreamCipherXorAtOffsetOperationProto(
    @ProtoNumber(21)
    val value: StreamCipherXorAtOffsetRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("twofish_cbc_pkcs7")
internal data class TwofishCbcPkcs7OperationProto(
    @ProtoNumber(22)
    val value: TwofishCbcPkcs7RequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("rsa_oaep_encrypt")
internal data class RsaOaepEncryptOperationProto(
    @ProtoNumber(23)
    val value: RsaOaepEncryptRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("rsa_oaep_decrypt")
internal data class RsaOaepDecryptOperationProto(
    @ProtoNumber(24)
    val value: RsaOaepDecryptRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("rsa_pkcs8_to_spki")
internal data class RsaPkcs8ToSpkiOperationProto(
    @ProtoNumber(25)
    val value: RsaPkcs8ToSpkiRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("ssh_agent_tcp_chacha20_poly1305")
internal data class SshAgentTcpChaCha20Poly1305OperationProto(
    @ProtoNumber(26)
    val value: SshAgentTcpChaCha20Poly1305RequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("ssh_key_generate")
internal data class SshKeyGenerateOperationProto(
    @ProtoNumber(27)
    val value: SshKeyGenerateRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("ssh_key_parse")
internal data class SshKeyParseOperationProto(
    @ProtoNumber(28)
    val value: SshKeyParseRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("ssh_key_describe")
internal data class SshKeyDescribeOperationProto(
    @ProtoNumber(29)
    val value: SshKeyDescribeRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("ssh_private_key_rsa_bits")
internal data class SshPrivateKeyRsaBitsOperationProto(
    @ProtoNumber(30)
    val value: SshPrivateKeyRsaBitsRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("ssh_private_key_format")
internal data class SshPrivateKeyFormatOperationProto(
    @ProtoNumber(31)
    val value: SshPrivateKeyFormatRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("ssh_agent_sign")
internal data class SshAgentSignOperationProto(
    @ProtoNumber(32)
    val value: SshAgentSignRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("ssh_private_key_import")
internal data class SshPrivateKeyImportOperationProto(
    @ProtoNumber(33)
    val value: SshPrivateKeyImportRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("ssh_key_export_cxf")
internal data class SshKeyExportCxfOperationProto(
    @ProtoNumber(51)
    val value: SshKeyExportCxfRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("passkey_key_generate")
internal data class PasskeyKeyGenerateOperationProto(
    @ProtoNumber(48)
    val value: PasskeyKeyGenerateRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("passkey_key_inspect")
internal data class PasskeyKeyInspectOperationProto(
    @ProtoNumber(49)
    val value: PasskeyKeyInspectRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("passkey_sign")
internal data class PasskeySignOperationProto(
    @ProtoNumber(50)
    val value: PasskeySignRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("open_pgp_public_key_parse")
internal data class OpenPgpPublicKeyParseOperationProto(
    @ProtoNumber(34)
    val value: OpenPgpPublicKeyParseRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("open_pgp_verify")
internal data class OpenPgpVerifyOperationProto(
    @ProtoNumber(35)
    val value: OpenPgpVerifyRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("open_pgp_metadata_resolve")
internal data class OpenPgpMetadataResolveOperationProto(
    @ProtoNumber(36)
    val value: OpenPgpMetadataResolveRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("open_pgp_key_generate")
internal data class OpenPgpKeyGenerateOperationProto(
    @ProtoNumber(37)
    val value: OpenPgpKeyGenerateRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("open_pgp_key_import")
internal data class OpenPgpKeyImportOperationProto(
    @ProtoNumber(38)
    val value: OpenPgpKeyImportRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("open_pgp_sign")
internal data class OpenPgpSignOperationProto(
    @ProtoNumber(39)
    val value: OpenPgpSignRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("open_pgp_encrypt")
internal data class OpenPgpEncryptOperationProto(
    @ProtoNumber(40)
    val value: OpenPgpEncryptRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("open_pgp_decrypt")
internal data class OpenPgpDecryptOperationProto(
    @ProtoNumber(41)
    val value: OpenPgpDecryptRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("open_pgp_expiration_update")
internal data class OpenPgpExpirationUpdateOperationProto(
    @ProtoNumber(42)
    val value: OpenPgpExpirationUpdateRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("open_pgp_agent_sign")
internal data class OpenPgpAgentSignOperationProto(
    @ProtoNumber(43)
    val value: OpenPgpAgentSignRequestProto,
) : NativeRequestOperationProto

@Serializable
@SerialName("open_pgp_agent_decrypt")
internal data class OpenPgpAgentDecryptOperationProto(
    @ProtoNumber(44)
    val value: OpenPgpAgentDecryptRequestProto,
) : NativeRequestOperationProto

@Serializable
internal data class NativeStreamOpenRequestProto(
    @ProtoNumber(1)
    val protocolVersion: Int,
    @ProtoOneOf
    val operation: NativeStreamOpenOperationProto,
)

@Serializable
internal sealed interface NativeStreamOpenOperationProto

@Serializable
@SerialName("hmac_sha256")
internal data class HmacSha256StreamOpenOperationProto(
    @ProtoNumber(10)
    val value: HmacSha256StreamOpenRequestProto,
) : NativeStreamOpenOperationProto

@Serializable
@SerialName("digest")
internal data class DigestStreamOpenOperationProto(
    @ProtoNumber(11)
    val value: DigestStreamOpenRequestProto,
) : NativeStreamOpenOperationProto

@Serializable
@SerialName("hmac")
internal data class HmacStreamOpenOperationProto(
    @ProtoNumber(12)
    val value: HmacStreamOpenRequestProto,
) : NativeStreamOpenOperationProto

@Serializable
@SerialName("aes_cbc_pkcs7")
internal data class AesCbcPkcs7StreamOpenOperationProto(
    @ProtoNumber(13)
    val value: AesCbcPkcs7StreamOpenRequestProto,
) : NativeStreamOpenOperationProto

@Serializable
@SerialName("twofish_cbc_pkcs7")
internal data class TwofishCbcPkcs7StreamOpenOperationProto(
    @ProtoNumber(14)
    val value: TwofishCbcPkcs7StreamOpenRequestProto,
) : NativeStreamOpenOperationProto

@Serializable
@SerialName("open_pgp_detached_verify")
internal data class OpenPgpDetachedVerifyStreamOpenOperationProto(
    @ProtoNumber(15)
    val value: OpenPgpDetachedVerifyStreamOpenRequestProto,
) : NativeStreamOpenOperationProto

@Serializable
@SerialName("open_pgp_detached_sign")
internal data class OpenPgpDetachedSignStreamOpenOperationProto(
    @ProtoNumber(16)
    val value: OpenPgpDetachedSignStreamOpenRequestProto,
) : NativeStreamOpenOperationProto

@Serializable
@SerialName("open_pgp_encrypt")
internal data class OpenPgpEncryptStreamOpenOperationProto(
    @ProtoNumber(17)
    val value: OpenPgpEncryptStreamOpenRequestProto,
) : NativeStreamOpenOperationProto

@Serializable
@SerialName("open_pgp_decrypt")
internal data class OpenPgpDecryptStreamOpenOperationProto(
    @ProtoNumber(18)
    val value: OpenPgpDecryptStreamOpenRequestProto,
) : NativeStreamOpenOperationProto

@Serializable
@SerialName("aes_cbc_pkcs7_hmac_sha256_encrypt")
internal data class AesCbcPkcs7HmacSha256EncryptStreamOpenOperationProto(
    @ProtoNumber(19)
    val value: AesCbcPkcs7HmacSha256EncryptStreamOpenRequestProto,
) : NativeStreamOpenOperationProto

@Serializable
@SerialName("aes_cbc_pkcs7_hmac_sha256_decrypt")
internal data class AesCbcPkcs7HmacSha256DecryptStreamOpenOperationProto(
    @ProtoNumber(20)
    val value: AesCbcPkcs7HmacSha256DecryptStreamOpenRequestProto,
) : NativeStreamOpenOperationProto

@Serializable
internal data class HmacSha256StreamOpenRequestProto(
    @ProtoNumber(1)
    val key: ByteArray,
)

@Serializable
internal data class DigestStreamOpenRequestProto(
    @ProtoNumber(1)
    val algorithm: HashAlgorithmProto,
)

@Serializable
internal data class HmacStreamOpenRequestProto(
    @ProtoNumber(1)
    val algorithm: HashAlgorithmProto,
    @ProtoNumber(2)
    val key: ByteArray,
)

@Serializable
internal data class AesCbcPkcs7StreamOpenRequestProto(
    @ProtoNumber(1)
    val direction: CipherDirectionProto,
    @ProtoNumber(2)
    val key: ByteArray,
    @ProtoNumber(3)
    val iv: ByteArray,
)

@Serializable
internal data class AesCbcPkcs7HmacSha256EncryptStreamOpenRequestProto(
    @ProtoNumber(1)
    val encryptionKey: ByteArray,
    @ProtoNumber(2)
    val macKey: ByteArray,
    @ProtoNumber(3)
    val iv: ByteArray,
)

@Serializable
internal data class AesCbcPkcs7HmacSha256DecryptStreamOpenRequestProto(
    @ProtoNumber(1)
    val encryptionKey: ByteArray,
    @ProtoNumber(2)
    val macKey: ByteArray,
    @ProtoNumber(3)
    val iv: ByteArray,
    @ProtoNumber(4)
    val expectedMac: ByteArray,
)

@Serializable
internal data class TwofishCbcPkcs7StreamOpenRequestProto(
    @ProtoNumber(1)
    val direction: CipherDirectionProto,
    @ProtoNumber(2)
    val key: ByteArray,
    @ProtoNumber(3)
    val iv: ByteArray,
)

@Serializable
internal data class HkdfSha256RequestProto(
    @ProtoNumber(1)
    val seed: ByteArray,
    @ProtoNumber(2)
    val salt: ByteArray? = null,
    @ProtoNumber(3)
    val info: ByteArray? = null,
    @ProtoNumber(4)
    val length: Int,
)

@Serializable
internal data class Pbkdf2Sha256RequestProto(
    @ProtoNumber(1)
    val seed: ByteArray,
    @ProtoNumber(2)
    val salt: ByteArray,
    @ProtoNumber(3)
    val iterations: Int,
    @ProtoNumber(4)
    val length: Int,
)

@Serializable
internal enum class Argon2ModeProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    D,

    @ProtoNumber(2)
    I,

    @ProtoNumber(3)
    ID,
}

@Serializable
internal data class Argon2RequestProto(
    @ProtoNumber(1)
    val mode: Argon2ModeProto,
    @ProtoNumber(2)
    val seed: ByteArray,
    @ProtoNumber(3)
    val salt: ByteArray,
    @ProtoNumber(4)
    val iterations: Int,
    @ProtoNumber(5)
    val memoryKib: Int,
    @ProtoNumber(6)
    val parallelism: Int,
    @ProtoNumber(7)
    val length: Int,
    @ProtoNumber(8)
    val version: Int = 0,
    @ProtoNumber(9)
    val secret: ByteArray? = null,
    @ProtoNumber(10)
    val associatedData: ByteArray? = null,
)

@Serializable
internal data class RandomBytesRequestProto(
    @ProtoNumber(1)
    val length: Int,
)

@Serializable
internal data class RandomIntRequestProto(
    @ProtoNumber(1)
    val bounded: Boolean,
    @ProtoNumber(2)
    val exclusiveUpperBound: Int = 0,
)

@Serializable
internal data class RandomIntsRequestProto(
    @ProtoNumber(1)
    val bounded: Boolean,
    @ProtoNumber(2)
    val exclusiveUpperBound: Int = 0,
    @ProtoNumber(3)
    val count: Int,
)

@Serializable
internal enum class HashAlgorithmProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    SHA1,

    @ProtoNumber(2)
    SHA256,

    @ProtoNumber(3)
    SHA512,

    @ProtoNumber(4)
    MD5,
}

@Serializable
internal data class HmacRequestProto(
    @ProtoNumber(1)
    val algorithm: HashAlgorithmProto,
    @ProtoNumber(2)
    val key: ByteArray,
    @ProtoNumber(3)
    val data: ByteArray,
)

@Serializable
internal data class DigestRequestProto(
    @ProtoNumber(1)
    val algorithm: HashAlgorithmProto,
    @ProtoNumber(2)
    val data: ByteArray,
)

@Serializable
internal data class AesEcbNoPaddingEncryptRequestProto(
    @ProtoNumber(1)
    val key: ByteArray,
    @ProtoNumber(2)
    val data: ByteArray,
)

@Serializable
internal data class AesEcbNoPaddingTransformRequestProto(
    @ProtoNumber(1)
    val key: ByteArray,
    @ProtoNumber(2)
    val data: ByteArray,
    @ProtoNumber(3)
    val rounds: Int,
)

@Serializable
internal enum class CipherDirectionProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    ENCRYPT,

    @ProtoNumber(2)
    DECRYPT,
}

@Serializable
internal data class AesCbcPkcs7RequestProto(
    @ProtoNumber(1)
    val direction: CipherDirectionProto,
    @ProtoNumber(2)
    val key: ByteArray,
    @ProtoNumber(3)
    val iv: ByteArray,
    @ProtoNumber(4)
    val data: ByteArray,
)

@Serializable
internal data class AesCbcPkcs7HmacSha256EncryptRequestProto(
    @ProtoNumber(1)
    val encryptionKey: ByteArray,
    @ProtoNumber(2)
    val macKey: ByteArray,
    @ProtoNumber(3)
    val iv: ByteArray,
    @ProtoNumber(4)
    val plaintext: ByteArray,
)

@Serializable
internal data class AesCbcPkcs7HmacSha256EncryptResultProto(
    @ProtoNumber(1)
    val ciphertext: ByteArray,
    @ProtoNumber(2)
    val mac: ByteArray,
)

@Serializable
internal data class AesCbcPkcs7HmacSha256DecryptRequestProto(
    @ProtoNumber(1)
    val encryptionKey: ByteArray,
    @ProtoNumber(2)
    val macKey: ByteArray,
    @ProtoNumber(3)
    val iv: ByteArray,
    @ProtoNumber(4)
    val ciphertext: ByteArray,
    @ProtoNumber(5)
    val expectedMac: ByteArray,
)

@Serializable
internal enum class StreamCipherAlgorithmProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    SALSA20,

    @ProtoNumber(2)
    CHACHA20,
}

@Serializable
internal data class StreamCipherXorAtOffsetRequestProto(
    @ProtoNumber(1)
    val algorithm: StreamCipherAlgorithmProto,
    @ProtoNumber(2)
    val key: ByteArray,
    @ProtoNumber(3)
    val nonce: ByteArray,
    @ProtoNumber(4)
    val offset: Long,
    @ProtoNumber(5)
    val data: ByteArray,
)

@Serializable
internal data class TwofishCbcPkcs7RequestProto(
    @ProtoNumber(1)
    val direction: CipherDirectionProto,
    @ProtoNumber(2)
    val key: ByteArray,
    @ProtoNumber(3)
    val iv: ByteArray,
    @ProtoNumber(4)
    val data: ByteArray,
)

@Serializable
internal enum class RsaOaepHashProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    SHA1,

    @ProtoNumber(2)
    SHA256,
}

@Serializable
internal data class RsaOaepDecryptRequestProto(
    @ProtoNumber(1)
    val hash: RsaOaepHashProto,
    @ProtoNumber(2)
    val privateKeyPkcs8: ByteArray,
    @ProtoNumber(3)
    val ciphertext: ByteArray,
)

@Serializable
internal data class RsaOaepEncryptRequestProto(
    @ProtoNumber(1)
    val hash: RsaOaepHashProto,
    @ProtoNumber(2)
    val publicKeySpki: ByteArray,
    @ProtoNumber(3)
    val plaintext: ByteArray,
)

@Serializable
internal data class RsaPkcs8ToSpkiRequestProto(
    @ProtoNumber(1)
    val privateKeyPkcs8: ByteArray,
)

@Serializable
internal data class SshAgentTcpChaCha20Poly1305RequestProto(
    @ProtoNumber(1)
    val direction: CipherDirectionProto,
    @ProtoNumber(2)
    val key: ByteArray,
    @ProtoNumber(3)
    val nonce: ByteArray,
    @ProtoNumber(4)
    val header: ByteArray,
    @ProtoNumber(5)
    val payload: ByteArray,
)

@Serializable
internal enum class SshKeyTypeProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    RSA,

    @ProtoNumber(2)
    ED25519,
}

@Serializable
internal data class SshKeyGenerateRequestProto(
    @ProtoNumber(1)
    val type: SshKeyTypeProto,
    @ProtoNumber(2)
    val rsaBits: Int = 0,
)

@Serializable
internal data class SshKeyParseRequestProto(
    @ProtoNumber(1)
    val privateKeyPem: String,
    @ProtoNumber(2)
    val publicKeyOpenSsh: String,
)

@Serializable
internal data class SshKeyDescribeRequestProto(
    @ProtoNumber(1)
    val type: SshKeyTypeProto,
    @ProtoNumber(2)
    val privateKey: ByteArray,
    @ProtoNumber(3)
    val publicKey: ByteArray,
)

@Serializable
internal data class SshPrivateKeyRsaBitsRequestProto(
    @ProtoNumber(1)
    val privateKey: ByteArray,
)

@Serializable
internal data class SshPrivateKeyFormatRequestProto(
    @ProtoNumber(1)
    val type: SshKeyTypeProto,
    @ProtoNumber(2)
    val privateKey: ByteArray,
)

@Serializable
internal data class SshAgentSignRequestProto(
    @ProtoNumber(1)
    val privateKeyPem: String,
    @ProtoNumber(2)
    val publicKeyOpenSsh: String? = null,
    @ProtoNumber(3)
    val data: ByteArray,
    @ProtoNumber(4)
    val flags: Int,
)

@Serializable
internal data class SshPrivateKeyImportRequestProto(
    @ProtoNumber(1)
    val content: String,
    @ProtoNumber(2)
    val passphraseUtf8: ByteArray? = null,
)

@Serializable
internal data class SshKeyExportCxfRequestProto(
    @ProtoNumber(1)
    val privateKeyPem: String,
    @ProtoNumber(2)
    val publicKeyOpenSsh: String,
)

@Serializable
internal data class SshKeyExportCxfResultProto(
    @ProtoNumber(1)
    val type: SshKeyTypeProto,
    @ProtoNumber(2)
    val privateKeyPkcs8: ByteArray,
)

@Serializable
internal enum class PasskeyAlgorithmProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    ES256,
}

@Serializable
internal enum class PasskeyKeyProfileProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    EC_P256,
}

@Serializable
internal enum class PasskeyKeyErrorProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    MALFORMED,

    @ProtoNumber(2)
    UNSUPPORTED,

    @ProtoNumber(3)
    RESOURCE_LIMIT,
}

@Serializable
internal data class PasskeyKeyGenerateRequestProto(
    @ProtoNumber(1)
    val algorithm: PasskeyAlgorithmProto,
)

@Serializable
internal data class PasskeyKeyInspectRequestProto(
    @ProtoNumber(1)
    val privateKeyPkcs8: ByteArray,
)

@Serializable
internal data class PasskeySignRequestProto(
    @ProtoNumber(1)
    val algorithm: PasskeyAlgorithmProto,
    @ProtoNumber(2)
    val privateKeyPkcs8: ByteArray,
    @ProtoNumber(3)
    val data: ByteArray,
)

@Serializable
internal data class PasskeyKeyMaterialProto(
    @ProtoNumber(1)
    val profile: PasskeyKeyProfileProto = PasskeyKeyProfileProto.UNSPECIFIED,
    @ProtoNumber(2)
    val privateKeyPkcs8: ByteArray = byteArrayOf(),
    @ProtoNumber(3)
    val publicKeyX: ByteArray = byteArrayOf(),
    @ProtoNumber(4)
    val publicKeyY: ByteArray = byteArrayOf(),
    @ProtoNumber(5)
    val publicKeySpki: ByteArray = byteArrayOf(),
)

@Serializable
internal data class PasskeyKeyInspectionProto(
    @ProtoNumber(1)
    val keyMaterial: PasskeyKeyMaterialProto? = null,
    @ProtoNumber(2)
    val error: PasskeyKeyErrorProto = PasskeyKeyErrorProto.UNSPECIFIED,
)

@Serializable
internal data class PasskeySignatureProto(
    @ProtoNumber(1)
    val algorithm: PasskeyAlgorithmProto = PasskeyAlgorithmProto.UNSPECIFIED,
    @ProtoNumber(2)
    val signatureDer: ByteArray = byteArrayOf(),
)

@Serializable
internal data class PasskeySignResultProto(
    @ProtoNumber(1)
    val signature: PasskeySignatureProto? = null,
    @ProtoNumber(2)
    val error: PasskeyKeyErrorProto = PasskeyKeyErrorProto.UNSPECIFIED,
)

@Serializable
internal data class SshKeyMaterialProto(
    @ProtoNumber(1)
    val type: SshKeyTypeProto = SshKeyTypeProto.UNSPECIFIED,
    @ProtoNumber(2)
    val privateKey: ByteArray = byteArrayOf(),
    @ProtoNumber(3)
    val publicKey: ByteArray = byteArrayOf(),
)

@Serializable
internal data class SshKeyDescriptionProto(
    @ProtoNumber(1)
    val privateKeyPem: String = "",
    @ProtoNumber(2)
    val publicKeyOpenSsh: String = "",
    @ProtoNumber(3)
    val privateFingerprint: String = "",
    @ProtoNumber(4)
    val publicFingerprint: String = "",
)

@Serializable
internal data class SshFormattedPrivateKeyProto(
    @ProtoNumber(1)
    val value: String = "",
)

@Serializable
internal data class SshSignatureProto(
    @ProtoNumber(1)
    val algorithm: String = "",
    @ProtoNumber(2)
    val signature: ByteArray = byteArrayOf(),
)

@Serializable
internal enum class SshPrivateKeyImportErrorReasonProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    UNSUPPORTED_FORMAT,

    @ProtoNumber(2)
    UNSUPPORTED_ALGORITHM,

    @ProtoNumber(3)
    INVALID_PASSPHRASE,

    @ProtoNumber(4)
    MALFORMED_KEY,
}

@Serializable
internal data class SshPrivateKeyImportSuccessProto(
    @ProtoNumber(1)
    val keyMaterial: SshKeyMaterialProto? = null,
)

@Serializable
internal data class SshPrivateKeyImportNeedsPassphraseProto(
    @ProtoNumber(1)
    val formatLabel: String = "",
)

@Serializable
internal data class SshPrivateKeyImportErrorProto(
    @ProtoNumber(1)
    val reason: SshPrivateKeyImportErrorReasonProto = SshPrivateKeyImportErrorReasonProto.UNSPECIFIED,
)

@Serializable
internal data class SshPrivateKeyImportResultProto(
    @ProtoOneOf
    val result: SshPrivateKeyImportOutcomeProto? = null,
)

@Serializable
internal sealed interface SshPrivateKeyImportOutcomeProto

@Serializable
@SerialName("success")
internal data class SshPrivateKeyImportSuccessOutcomeProto(
    @ProtoNumber(1)
    val value: SshPrivateKeyImportSuccessProto,
) : SshPrivateKeyImportOutcomeProto

@Serializable
@SerialName("needs_passphrase")
internal data class SshPrivateKeyImportNeedsPassphraseOutcomeProto(
    @ProtoNumber(2)
    val value: SshPrivateKeyImportNeedsPassphraseProto,
) : SshPrivateKeyImportOutcomeProto

@Serializable
@SerialName("error")
internal data class SshPrivateKeyImportErrorOutcomeProto(
    @ProtoNumber(3)
    val value: SshPrivateKeyImportErrorProto,
) : SshPrivateKeyImportOutcomeProto

@Serializable
internal data class OpenPgpPublicKeyParseRequestProto(
    @ProtoNumber(1)
    val keyData: ByteArray,
    @ProtoNumber(2)
    val referenceTimeEpochSeconds: Long? = null,
)

@Serializable
internal enum class OpenPgpVerifyKindProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    CLEAR_TEXT,

    @ProtoNumber(2)
    DETACHED,
}

@Serializable
internal data class OpenPgpVerifyRequestProto(
    @ProtoNumber(1)
    val kind: OpenPgpVerifyKindProto,
    @ProtoNumber(2)
    val content: ByteArray,
    @ProtoNumber(3)
    val signature: ByteArray = byteArrayOf(),
    @ProtoNumber(4)
    val publicKeys: List<ByteArray>,
    @ProtoNumber(5)
    val referenceTimeEpochSeconds: Long? = null,
)

@Serializable
internal data class OpenPgpDetachedVerifyStreamOpenRequestProto(
    @ProtoNumber(1)
    val signature: ByteArray,
    @ProtoNumber(2)
    val publicKeys: List<ByteArray>,
    @ProtoNumber(3)
    val referenceTimeEpochSeconds: Long? = null,
)

@Serializable
internal data class OpenPgpMetadataResolveRequestProto(
    @ProtoNumber(1)
    val privateKeyData: ByteArray? = null,
    @ProtoNumber(2)
    val publicKeyData: ByteArray? = null,
    @ProtoNumber(3)
    val normalizedFingerprint: String = "",
    @ProtoNumber(4)
    val candidateRevocationKeys: List<ByteArray> = emptyList(),
    @ProtoNumber(5)
    val referenceTimeEpochSeconds: Long? = null,
)

@Serializable
internal enum class OpenPgpPublicKeyParseErrorReasonProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    EMPTY,

    @ProtoNumber(2)
    MALFORMED,

    @ProtoNumber(3)
    UNSUPPORTED_KEY_VERSION,
}

@Serializable
internal data class OpenPgpPublicSubKeyInfoProto(
    @ProtoNumber(1)
    val fingerprint: String = "",
    @ProtoNumber(2)
    val keygrip: String? = null,
    @ProtoNumber(3)
    val keyId: String = "",
    @ProtoNumber(4)
    val algorithm: String = "",
    @ProtoNumber(5)
    val bitStrength: Int? = null,
    @ProtoNumber(6)
    val canSign: Boolean = false,
    @ProtoNumber(7)
    val canEncrypt: Boolean = false,
    @ProtoNumber(8)
    val revoked: Boolean = false,
    @ProtoNumber(9)
    val createdAtEpochSeconds: Long? = null,
    @ProtoNumber(10)
    val expiresAtEpochSeconds: Long? = null,
)

@Serializable
internal data class OpenPgpPublicKeyInfoProto(
    @ProtoNumber(1)
    val fingerprint: String = "",
    @ProtoNumber(2)
    val keygrip: String? = null,
    @ProtoNumber(3)
    val keyId: String = "",
    @ProtoNumber(4)
    val algorithm: String = "",
    @ProtoNumber(5)
    val bitStrength: Int? = null,
    @ProtoNumber(6)
    val userIds: List<String> = emptyList(),
    @ProtoNumber(7)
    val emails: List<String> = emptyList(),
    @ProtoNumber(8)
    val createdAtEpochSeconds: Long? = null,
    @ProtoNumber(9)
    val expiresAtEpochSeconds: Long? = null,
    @ProtoNumber(10)
    val revoked: Boolean = false,
    @ProtoNumber(11)
    val canSign: Boolean = false,
    @ProtoNumber(12)
    val canEncrypt: Boolean = false,
    @ProtoNumber(13)
    val publicKeyArmored: String = "",
    @ProtoNumber(14)
    val subkeys: List<OpenPgpPublicSubKeyInfoProto> = emptyList(),
)

@Serializable
internal data class OpenPgpPublicKeyParseSuccessProto(
    @ProtoNumber(1)
    val keys: List<OpenPgpPublicKeyInfoProto> = emptyList(),
)

@Serializable
internal data class OpenPgpPublicKeyParseErrorProto(
    @ProtoNumber(1)
    val reason: OpenPgpPublicKeyParseErrorReasonProto =
        OpenPgpPublicKeyParseErrorReasonProto.UNSPECIFIED,
)

@Serializable
internal data class OpenPgpPublicKeyParseResultProto(
    @ProtoOneOf
    val result: OpenPgpPublicKeyParseOutcomeProto? = null,
)

@Serializable
internal sealed interface OpenPgpPublicKeyParseOutcomeProto

@Serializable
@SerialName("success")
internal data class OpenPgpPublicKeyParseSuccessOutcomeProto(
    @ProtoNumber(1)
    val value: OpenPgpPublicKeyParseSuccessProto,
) : OpenPgpPublicKeyParseOutcomeProto

@Serializable
@SerialName("error")
internal data class OpenPgpPublicKeyParseErrorOutcomeProto(
    @ProtoNumber(2)
    val value: OpenPgpPublicKeyParseErrorProto,
) : OpenPgpPublicKeyParseOutcomeProto

@Serializable
internal enum class OpenPgpVerificationStatusProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    VALID,

    @ProtoNumber(2)
    INVALID,

    @ProtoNumber(3)
    MISSING_PUBLIC_KEY,
}

internal enum class OpenPgpVerificationWarningProto(
    val wireValue: Int,
) {
    UNSPECIFIED(wireValue = 0),
    KEY_REVOKED(wireValue = 1),
    KEY_EXPIRED(wireValue = 2),
    SIGNATURE_EXPIRED(wireValue = 3),
    ;

    companion object {
        fun fromWireValue(value: Int): OpenPgpVerificationWarningProto? =
            entries.firstOrNull { entry -> entry.wireValue == value }
    }
}

@Serializable
internal data class OpenPgpVerificationProto(
    @ProtoNumber(1)
    val status: OpenPgpVerificationStatusProto = OpenPgpVerificationStatusProto.UNSPECIFIED,
    @ProtoNumber(2)
    val keyId: String = "",
    @ProtoNumber(3)
    val fingerprint: String? = null,
    @ProtoNumber(4)
    val userIds: List<String> = emptyList(),
    @ProtoNumber(5)
    val createdAtEpochSeconds: Long? = null,
    @ProtoNumber(6)
    @ProtoPacked
    val warnings: List<Int> = emptyList(),
)

@Serializable
internal data class OpenPgpKeyMetadataKeyProto(
    @ProtoNumber(1)
    val keygrip: String = "",
    @ProtoNumber(2)
    val fingerprint: String = "",
    @ProtoNumber(3)
    val algorithm: String = "",
    @ProtoNumber(4)
    val capabilities: List<String> = emptyList(),
)

@Serializable
internal data class OpenPgpKeyMetadataProto(
    @ProtoNumber(1)
    val version: Int = 0,
    @ProtoNumber(2)
    val keys: List<OpenPgpKeyMetadataKeyProto> = emptyList(),
)

@Serializable
internal data class OpenPgpMetadataResolveResultProto(
    @ProtoNumber(1)
    val metadata: OpenPgpKeyMetadataProto? = null,
)

@Serializable
internal enum class OpenPgpKeyKindProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    LEGACY_ED25519_X25519,

    @ProtoNumber(2)
    RSA,
}

@Serializable
internal data class OpenPgpKeyGenerateRequestProto(
    @ProtoNumber(1)
    val kind: OpenPgpKeyKindProto,
    @ProtoNumber(2)
    val userId: String,
    @ProtoNumber(3)
    val rsaBits: Int = 0,
    @ProtoNumber(4)
    val creationTimeEpochSeconds: Long,
    @ProtoNumber(5)
    @Serializable(with = ProtoUInt32Serializer::class)
    val expirationSeconds: UInt? = null,
)

internal object ProtoUInt32Serializer : KSerializer<UInt> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        serialName = "com.artemchep.keyguard.nativecrypto.ProtoUInt32",
        kind = PrimitiveKind.LONG,
    )

    override fun serialize(encoder: Encoder, value: UInt) {
        encoder.encodeLong(value.toLong())
    }

    override fun deserialize(decoder: Decoder): UInt {
        val value = decoder.decodeLong()
        if (value !in 0L..UInt.MAX_VALUE.toLong()) {
            throw SerializationException("uint32 value is out of range")
        }
        return value.toUInt()
    }
}

@Serializable
internal data class OpenPgpKeyMaterialProto(
    @ProtoNumber(1)
    val privateKeyArmored: ByteArray = byteArrayOf(),
    @ProtoNumber(2)
    val publicKeyArmored: ByteArray = byteArrayOf(),
    @ProtoNumber(3)
    val fingerprint: String = "",
)

@Serializable
internal data class OpenPgpKeyImportRequestProto(
    @ProtoNumber(1)
    val keyData: ByteArray,
    @ProtoNumber(2)
    val passphraseUtf8: ByteArray? = null,
    @ProtoNumber(3)
    val referenceTimeEpochSeconds: Long? = null,
)

@Serializable
internal enum class OpenPgpKeyImportErrorReasonProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    EMPTY,

    @ProtoNumber(2)
    UNSUPPORTED_FORMAT,

    @ProtoNumber(3)
    INVALID_PASSPHRASE,

    @ProtoNumber(4)
    MALFORMED_KEY,
}

@Serializable
internal data class OpenPgpKeyImportSuccessProto(
    @ProtoNumber(1)
    val keyMaterial: OpenPgpKeyMaterialProto? = null,
)

@Serializable
internal data class OpenPgpKeyImportNeedsPassphraseProto(
    @ProtoNumber(1)
    val formatLabel: String = "",
)

@Serializable
internal data class OpenPgpKeyImportErrorProto(
    @ProtoNumber(1)
    val reason: OpenPgpKeyImportErrorReasonProto = OpenPgpKeyImportErrorReasonProto.UNSPECIFIED,
)

@Serializable
internal data class OpenPgpKeyImportResultProto(
    @ProtoOneOf
    val result: OpenPgpKeyImportOutcomeProto? = null,
)

@Serializable
internal sealed interface OpenPgpKeyImportOutcomeProto

@Serializable
@SerialName("success")
internal data class OpenPgpKeyImportSuccessOutcomeProto(
    @ProtoNumber(1)
    val value: OpenPgpKeyImportSuccessProto,
) : OpenPgpKeyImportOutcomeProto

@Serializable
@SerialName("needs_passphrase")
internal data class OpenPgpKeyImportNeedsPassphraseOutcomeProto(
    @ProtoNumber(2)
    val value: OpenPgpKeyImportNeedsPassphraseProto,
) : OpenPgpKeyImportOutcomeProto

@Serializable
@SerialName("error")
internal data class OpenPgpKeyImportErrorOutcomeProto(
    @ProtoNumber(3)
    val value: OpenPgpKeyImportErrorProto,
) : OpenPgpKeyImportOutcomeProto

@Serializable
internal enum class OpenPgpSignKindProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    CLEAR_TEXT,

    @ProtoNumber(2)
    DETACHED,
}

@Serializable
internal data class OpenPgpSignRequestProto(
    @ProtoNumber(1)
    val kind: OpenPgpSignKindProto,
    @ProtoNumber(2)
    val content: ByteArray,
    @ProtoNumber(3)
    val privateKey: ByteArray,
    @ProtoNumber(4)
    val preferredFingerprint: String = "",
    @ProtoNumber(5)
    val armored: Boolean,
    @ProtoNumber(6)
    val signatureTimeEpochSeconds: Long? = null,
    @ProtoNumber(7)
    val referenceTimeEpochSeconds: Long? = null,
)

@Serializable
internal data class OpenPgpDetachedSignStreamOpenRequestProto(
    @ProtoNumber(1)
    val privateKey: ByteArray,
    @ProtoNumber(2)
    val preferredFingerprint: String = "",
    @ProtoNumber(3)
    val armored: Boolean,
    @ProtoNumber(4)
    val signatureTimeEpochSeconds: Long? = null,
    @ProtoNumber(5)
    val referenceTimeEpochSeconds: Long? = null,
)

@Serializable
internal data class OpenPgpEncryptRequestProto(
    @ProtoNumber(1)
    val content: ByteArray,
    @ProtoNumber(2)
    val publicKeys: List<ByteArray>,
    @ProtoNumber(3)
    val signingPrivateKey: ByteArray? = null,
    @ProtoNumber(4)
    val preferredSigningFingerprint: String = "",
    @ProtoNumber(5)
    val fileName: String,
    @ProtoNumber(6)
    val armored: Boolean,
    @ProtoNumber(7)
    val literalTimeEpochSeconds: Long? = null,
    @ProtoNumber(8)
    val referenceTimeEpochSeconds: Long? = null,
)

@Serializable
internal data class OpenPgpEncryptStreamOpenRequestProto(
    @ProtoNumber(1)
    val publicKeys: List<ByteArray>,
    @ProtoNumber(2)
    val signingPrivateKey: ByteArray? = null,
    @ProtoNumber(3)
    val preferredSigningFingerprint: String = "",
    @ProtoNumber(4)
    val fileName: String,
    @ProtoNumber(5)
    val armored: Boolean,
    @ProtoNumber(6)
    val literalTimeEpochSeconds: Long? = null,
    @ProtoNumber(7)
    val referenceTimeEpochSeconds: Long? = null,
)

@Serializable
internal enum class OpenPgpProtectionModeProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    SEIPD_V1_MDC,

    @ProtoNumber(2)
    GNUPG_OCB,
}

@Serializable
internal data class OpenPgpEncryptResultProto(
    @ProtoNumber(1)
    val data: ByteArray = byteArrayOf(),
    @ProtoNumber(2)
    val protectionMode: OpenPgpProtectionModeProto = OpenPgpProtectionModeProto.UNSPECIFIED,
)

@Serializable
internal data class OpenPgpEncryptFinalProto(
    @ProtoNumber(1)
    val data: ByteArray = byteArrayOf(),
    @ProtoNumber(2)
    val protectionMode: OpenPgpProtectionModeProto = OpenPgpProtectionModeProto.UNSPECIFIED,
)

@Serializable
internal data class OpenPgpDecryptRequestProto(
    @ProtoNumber(1)
    val content: ByteArray,
    @ProtoNumber(2)
    val privateKeys: List<ByteArray>,
    @ProtoNumber(3)
    val verificationPublicKeys: List<ByteArray>,
    @ProtoNumber(4)
    val referenceTimeEpochSeconds: Long? = null,
)

@Serializable
internal data class OpenPgpDecryptStreamOpenRequestProto(
    @ProtoNumber(1)
    val privateKeys: List<ByteArray>,
    @ProtoNumber(2)
    val verificationPublicKeys: List<ByteArray>,
    @ProtoNumber(3)
    val referenceTimeEpochSeconds: Long? = null,
)

@Serializable
internal data class OpenPgpDecryptResultProto(
    @ProtoNumber(1)
    val data: ByteArray = byteArrayOf(),
    @ProtoNumber(2)
    val verification: OpenPgpVerificationProto? = null,
)

@Serializable
internal data class OpenPgpDecryptFinalProto(
    @ProtoNumber(1)
    val data: ByteArray = byteArrayOf(),
    @ProtoNumber(2)
    val verification: OpenPgpVerificationProto? = null,
)

@Serializable
internal data class OpenPgpExpirationUpdateRequestProto(
    @ProtoNumber(1)
    val privateKey: ByteArray,
    @ProtoNumber(2)
    val publicKey: ByteArray,
    @ProtoNumber(3)
    val expectedPrimaryFingerprint: String,
    @ProtoNumber(4)
    val componentFingerprints: List<String>,
    @ProtoNumber(5)
    val expiresAtEpochSeconds: Long? = null,
    @ProtoNumber(6)
    val candidateRevocationKeys: List<ByteArray> = emptyList(),
    @ProtoNumber(7)
    val referenceTimeEpochSeconds: Long,
)

@Serializable
internal enum class OpenPgpExpirationUpdateErrorReasonProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    EMPTY_PRIVATE_KEY,

    @ProtoNumber(2)
    MALFORMED_KEY,

    @ProtoNumber(3)
    FINGERPRINT_MISMATCH,

    @ProtoNumber(4)
    NO_COMPONENTS_SELECTED,

    @ProtoNumber(5)
    COMPONENT_NOT_FOUND,

    @ProtoNumber(6)
    REVOKED_COMPONENT,

    @ProtoNumber(7)
    UNRESOLVED_REVOCATION_AUTHORITY,

    @ProtoNumber(8)
    UNSUPPORTED_KEY_VERSION,

    @ProtoNumber(9)
    MISSING_SECRET_KEY,

    @ProtoNumber(10)
    PROTECTED_SECRET_KEY,

    @ProtoNumber(11)
    MISSING_SELF_SIGNATURE,

    @ProtoNumber(12)
    INVALID_EXPIRATION,

    @ProtoNumber(13)
    TIME_CONFLICT,

    @ProtoNumber(14)
    SIGNATURE_VERIFICATION_FAILED,

    @ProtoNumber(15)
    METADATA_RESOLUTION_FAILED,

    @ProtoNumber(16)
    INTERNAL_FAILURE,
}

@Serializable
internal data class OpenPgpExpirationUpdateSuccessProto(
    @ProtoNumber(1)
    val keyMaterial: OpenPgpKeyMaterialProto? = null,
    @ProtoNumber(2)
    val metadata: OpenPgpKeyMetadataProto? = null,
)

@Serializable
internal data class OpenPgpExpirationUpdateErrorProto(
    @ProtoNumber(1)
    val reason: OpenPgpExpirationUpdateErrorReasonProto =
        OpenPgpExpirationUpdateErrorReasonProto.UNSPECIFIED,
)

@Serializable
internal data class OpenPgpExpirationUpdateResultProto(
    @ProtoOneOf
    val result: OpenPgpExpirationUpdateOutcomeProto? = null,
)

@Serializable
internal sealed interface OpenPgpExpirationUpdateOutcomeProto

@Serializable
@SerialName("success")
internal data class OpenPgpExpirationUpdateSuccessOutcomeProto(
    @ProtoNumber(1)
    val value: OpenPgpExpirationUpdateSuccessProto,
) : OpenPgpExpirationUpdateOutcomeProto

@Serializable
@SerialName("error")
internal data class OpenPgpExpirationUpdateErrorOutcomeProto(
    @ProtoNumber(2)
    val value: OpenPgpExpirationUpdateErrorProto,
) : OpenPgpExpirationUpdateOutcomeProto

@Serializable
internal enum class OpenPgpAgentErrorReasonProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    KEY_NOT_FOUND,

    @ProtoNumber(2)
    UNSUPPORTED_ALGORITHM,
}

@Serializable
internal data class OpenPgpAgentSignRequestProto(
    @ProtoNumber(1)
    val privateKey: ByteArray,
    @ProtoNumber(2)
    val preferredFingerprint: String,
    @ProtoNumber(3)
    val hashAlgorithm: String,
    @ProtoNumber(4)
    val hash: ByteArray,
)

@Serializable
internal data class OpenPgpAgentSignSuccessProto(
    @ProtoNumber(1)
    val canonicalSexp: ByteArray = byteArrayOf(),
)

@Serializable
internal data class OpenPgpAgentErrorProto(
    @ProtoNumber(1)
    val reason: OpenPgpAgentErrorReasonProto = OpenPgpAgentErrorReasonProto.UNSPECIFIED,
)

@Serializable
internal data class OpenPgpAgentSignResultProto(
    @ProtoOneOf
    val result: OpenPgpAgentSignOutcomeProto? = null,
)

@Serializable
internal sealed interface OpenPgpAgentSignOutcomeProto

@Serializable
@SerialName("success")
internal data class OpenPgpAgentSignSuccessOutcomeProto(
    @ProtoNumber(1)
    val value: OpenPgpAgentSignSuccessProto,
) : OpenPgpAgentSignOutcomeProto

@Serializable
@SerialName("error")
internal data class OpenPgpAgentSignErrorOutcomeProto(
    @ProtoNumber(2)
    val value: OpenPgpAgentErrorProto,
) : OpenPgpAgentSignOutcomeProto

@Serializable
internal data class OpenPgpAgentDecryptRequestProto(
    @ProtoNumber(1)
    val privateKey: ByteArray,
    @ProtoNumber(2)
    val preferredFingerprint: String,
    @ProtoNumber(3)
    val ciphertext: ByteArray,
    @ProtoNumber(4)
    val unwrapEcdh: Boolean,
)

@Serializable
internal data class OpenPgpAgentDecryptSuccessProto(
    @ProtoNumber(1)
    val canonicalSexp: ByteArray = byteArrayOf(),
)

@Serializable
internal data class OpenPgpAgentDecryptResultProto(
    @ProtoOneOf
    val result: OpenPgpAgentDecryptOutcomeProto? = null,
)

@Serializable
internal sealed interface OpenPgpAgentDecryptOutcomeProto

@Serializable
@SerialName("success")
internal data class OpenPgpAgentDecryptSuccessOutcomeProto(
    @ProtoNumber(1)
    val value: OpenPgpAgentDecryptSuccessProto,
) : OpenPgpAgentDecryptOutcomeProto

@Serializable
@SerialName("error")
internal data class OpenPgpAgentDecryptErrorOutcomeProto(
    @ProtoNumber(2)
    val value: OpenPgpAgentErrorProto,
) : OpenPgpAgentDecryptOutcomeProto

@Serializable
internal data class NativeResponseProto(
    @ProtoNumber(1)
    val protocolVersion: Int = 0,
    @ProtoNumber(2)
    val status: NativeStatusProto? = null,
    @ProtoOneOf
    val result: NativeResponseResultProto? = null,
)

@Serializable
internal sealed interface NativeResponseResultProto

@Serializable
@SerialName("bytes_value")
internal data class BytesResultProto(
    @ProtoNumber(10)
    val value: ByteArray,
) : NativeResponseResultProto

@Serializable
@SerialName("int32_value")
internal data class Int32ResultProto(
    @ProtoNumber(11)
    @ProtoType(ProtoIntegerType.SIGNED)
    val value: Int,
) : NativeResponseResultProto

@Serializable
@SerialName("uint64_value")
internal data class UInt64ResultProto(
    @ProtoNumber(12)
    val value: Long,
) : NativeResponseResultProto

@Serializable
internal enum class NativeErrorCodeProto {
    @ProtoNumber(0)
    OK,

    @ProtoNumber(1)
    INVALID_REQUEST,

    @ProtoNumber(2)
    UNSUPPORTED_PROTOCOL,

    @ProtoNumber(3)
    INVALID_ARGUMENT,

    @ProtoNumber(4)
    RESOURCE_LIMIT,

    @ProtoNumber(5)
    CRYPTO_FAILURE,

    @ProtoNumber(6)
    AUTHENTICATION_FAILED,

    @ProtoNumber(7)
    INVALID_SESSION,

    @ProtoNumber(8)
    PANIC,

    @ProtoNumber(9)
    INTERNAL,

    @ProtoNumber(10)
    UNSUPPORTED_KEY_VERSION,

    @ProtoNumber(11)
    NO_USABLE_KEY,
}

/** Maps a non-OK wire status to its public error code; OK never reaches callers as a failure. */
internal fun NativeErrorCodeProto.toErrorCode(): NativeCryptoErrorCode = when (this) {
    NativeErrorCodeProto.OK,
    NativeErrorCodeProto.INTERNAL,
    -> NativeCryptoErrorCode.INTERNAL

    NativeErrorCodeProto.INVALID_REQUEST -> NativeCryptoErrorCode.INVALID_REQUEST
    NativeErrorCodeProto.UNSUPPORTED_PROTOCOL -> NativeCryptoErrorCode.UNSUPPORTED_PROTOCOL
    NativeErrorCodeProto.INVALID_ARGUMENT -> NativeCryptoErrorCode.INVALID_ARGUMENT
    NativeErrorCodeProto.RESOURCE_LIMIT -> NativeCryptoErrorCode.RESOURCE_LIMIT
    NativeErrorCodeProto.CRYPTO_FAILURE -> NativeCryptoErrorCode.CRYPTO_FAILURE
    NativeErrorCodeProto.AUTHENTICATION_FAILED -> NativeCryptoErrorCode.AUTHENTICATION_FAILED
    NativeErrorCodeProto.INVALID_SESSION -> NativeCryptoErrorCode.INVALID_SESSION
    NativeErrorCodeProto.PANIC -> NativeCryptoErrorCode.PANIC
    NativeErrorCodeProto.UNSUPPORTED_KEY_VERSION -> NativeCryptoErrorCode.UNSUPPORTED_KEY_VERSION
    NativeErrorCodeProto.NO_USABLE_KEY -> NativeCryptoErrorCode.NO_USABLE_KEY
}

@Serializable
internal data class NativeStatusProto(
    @ProtoNumber(1)
    val code: NativeErrorCodeProto = NativeErrorCodeProto.OK,
    @ProtoNumber(2)
    val operation: String = "",
)

internal fun NativeResponseResultProto.requireBytes(operation: String): ByteArray =
    (this as? BytesResultProto)?.value
        ?: throw NativeCryptoException(
            operation = operation,
            code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
        )
