package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.exception.DecodeException
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.provider.bitwarden.crypto.AsymmetricCryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.DecodeResult
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPublicKey
import org.bouncycastle.crypto.AsymmetricBlockCipher
import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.CipherParameters
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.encodings.OAEPEncoding
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.kodein.di.DirectDI
import org.kodein.di.instance

class CipherEncryptorImpl(
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
) : CipherEncryptor {
    companion object {
        private const val CIPHER_DIVIDER = "|"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
    )

    override fun decode2(
        cipher: String,
        symmetricCryptoKey: SymmetricCryptoKey2?,
        asymmetricCryptoKey: AsymmetricCryptoKey?,
    ): DecodeResult = kotlin.runCatching {
        performDecode(
            cipher = cipher,
            symmetricCryptoKey = symmetricCryptoKey,
            asymmetricCryptoKey = asymmetricCryptoKey,
        )
    }.getOrElse { e ->
        val msg = kotlin.run {
            val cipherSeq = cipher
                .splitToSequence(".", limit = 1)
                .firstOrNull()
            "Failed to decode a cipher-text '${cipherSeq.orEmpty()}.???'!"
        }
        throw DecodeException(msg, e)
    }

    private fun performDecode(
        cipher: String,
        symmetricCryptoKey: SymmetricCryptoKey2?,
        asymmetricCryptoKey: AsymmetricCryptoKey?,
    ): DecodeResult {
        // The string must have a following format:
        // {cipher-type-raw}.{cipher-content}
        val cipherSeq = cipher
            .splitToSequence(".", limit = 3)
            .toList()
        require(cipherSeq.size == 2) {
            val c = cipherSeq
                .dropLast(1)
                .joinToString(separator = ".") { it }
            "Cipher-text '$c.???' is not valid!"
        }
        val (cipherTypeRaw, cipherContent) = cipherSeq
        val cipherType = CipherEncryptor.Type.values()
            .firstOrNull { it.type == cipherTypeRaw }
            ?: error("Cipher type $cipherTypeRaw is not supported!")
        val cipherArgs = cipherContent
            .split(CIPHER_DIVIDER)
            .map { base64 ->
                base64Service.decode(base64)
            }

        fun requireSymmetricCryptoKey() =
            requireNotNull(symmetricCryptoKey) {
                "Symmetric Crypto Key must not be null, " +
                        "for decoding $cipherType."
            }

        val data = when (cipherType) {
            //
            // Symmetric
            //

            CipherEncryptor.Type.AesCbc256_B64 -> {
                val baseKey = requireSymmetricCryptoKey()
                kotlin
                    .runCatching {
                        val cryptoKey = baseKey.requireAesCbc256_B64()
                        decodeAesCbc256_B64(cipherArgs, encKey = cryptoKey.encKey)
                    }
                    .getOrElse {
                        // Try to fallback to decoding with a mac key.
                        val cryptoKey = baseKey.requireAesCbc256_HmacSha256_B64()
                        decodeAesCbc256_B64(
                            cipherArgs,
                            encKey = cryptoKey.encKey,
                        )
                    }
            }

            CipherEncryptor.Type.AesCbc128_HmacSha256_B64 -> {
                val cryptoKey = requireSymmetricCryptoKey().requireAesCbc128_HmacSha256_B64()
                decodeAesCbc128_HmacSha256_B64(
                    cipherArgs,
                    encKey = cryptoKey.encKey,
                    macKey = cryptoKey.macKey,
                )
            }

            CipherEncryptor.Type.AesCbc256_HmacSha256_B64 -> {
                val baseKey = requireSymmetricCryptoKey()
                kotlin
                    .runCatching {
                        val cryptoKey = baseKey.requireAesCbc256_HmacSha256_B64()
                        decodeAesCbc256_HmacSha256_B64(
                            cipherArgs,
                            encKey = cryptoKey.encKey,
                            macKey = cryptoKey.macKey,
                        )
                    }
                    .getOrElse {
                        // Try to fallback to decoding without mac key.
                        val cryptoKey = kotlin.run {
                            val rawKey = baseKey.data
                                .sliceArray(0 until 32)
                            SymmetricCryptoKey2.Crypto(
                                encKey = rawKey,
                            )
                        }
                        decodeAesCbc256_B64(
                            cipherArgs,
                            encKey = cryptoKey.encKey,
                        )
                    }
            }

            //
            // Asymmetric
            //

            CipherEncryptor.Type.Rsa2048_OaepSha256_B64 -> {
                requireNotNull(asymmetricCryptoKey) {
                    "Asymmetric Crypto Key must not be null, " +
                            "for decoding $cipherType."
                }

                decodeRsa2048_OaepSha256_B64(
                    cipherContent,
                    privateKey = asymmetricCryptoKey.privateKey,
                )
            }

            CipherEncryptor.Type.Rsa2048_OaepSha1_B64 -> {
                requireNotNull(asymmetricCryptoKey) {
                    "Asymmetric Crypto Key must not be null, " +
                            "for decoding $cipherType."
                }

                decodeRsa2048_OaepSha1_B64(
                    cipherContent,
                    privateKey = asymmetricCryptoKey.privateKey,
                )
            }

            CipherEncryptor.Type.Rsa2048_OaepSha256_HmacSha256_B64 -> {
                TODO("Decoding cipher type $cipherType is not supported yet.")
            }

            CipherEncryptor.Type.Rsa2048_OaepSha1_HmacSha256_B64 -> {
                TODO("Decoding cipher type $cipherType is not supported yet.")
            }
        }
        return DecodeResult(
            data = data,
            type = cipherType,
        )
    }

    private fun decodeAesCbc256_B64(
        args: List<ByteArray>,
        encKey: ByteArray,
    ): ByteArray {
        check(args.size >= 2) {
            "The cipher must consist of exactly 2 parts: iv, ct. The current cipher " +
                    "contains ${args.size} parts which may cause unknown behaviour!"
        }
        val (iv, ct) = args
        return decodeAesCbc256_HmacSha256_B64(
            iv = iv,
            ct = ct,
            encKey = encKey,
        )
    }

    private fun decodeAesCbc128_HmacSha256_B64(
        args: List<ByteArray>,
        encKey: ByteArray,
        macKey: ByteArray,
    ): ByteArray {
        require(encKey.size == 16) {
            "Aes-Cbc-128 requires a 16-byte key!"
        }
        return decodeAesCbc256_HmacSha256_B64(
            args = args,
            encKey = encKey,
            macKey = macKey,
        )
    }

    private fun decodeAesCbc256_HmacSha256_B64(
        args: List<ByteArray>,
        encKey: ByteArray,
        macKey: ByteArray,
    ): ByteArray {
        check(args.size == 3) {
            "The cipher must consist of exactly 3 parts: iv, ct, mac. The current cipher " +
                    "contains ${args.size} parts which may cause unknown behaviour!"
        }
        val (iv, ct, mac) = args
        // Check if the mac matches the provided one, otherwise
        // the cipher has been corrupted.
//        val computedMac = kotlin.run {
//            val macData = iv + ct
//            cryptoGenerator.hmacSha256(macKey, macData)
//        }
//        if (!computedMac.contentEquals(mac)) {
//            error("Message authentication codes do not match!")
//        }
        return decodeAesCbc256_HmacSha256_B64(
            iv = iv,
            ct = ct,
            encKey = encKey,
        )
    }

    private fun decodeAesCbc256_HmacSha256_B64(
        iv: ByteArray,
        ct: ByteArray,
        encKey: ByteArray,
    ): ByteArray {
        val aes = createAesCbc(iv, encKey, forEncryption = false)
        return cipherData(aes, ct)
    }

    private fun createAesCbc(
        iv: ByteArray,
        key: ByteArray,
        forEncryption: Boolean,
    ) = kotlin.run {
        val aes = PaddedBufferedBlockCipher(
            CBCBlockCipher(
                AESEngine(),
            ),
            PKCS7Padding(),
        )
        val ivAndKey: CipherParameters = ParametersWithIV(KeyParameter(key), iv)
        aes.init(forEncryption, ivAndKey)
        aes
    }

    @Throws(Exception::class)
    private fun cipherData(cipher: BufferedBlockCipher, data: ByteArray): ByteArray {
        val minSize = cipher.getOutputSize(data.size)
        val outBuf = ByteArray(minSize)
        val length1 = cipher.processBytes(data, 0, data.size, outBuf, 0)
        val length2 = cipher.doFinal(outBuf, length1)
        val actualLength = length1 + length2
        val result = ByteArray(actualLength)
        System.arraycopy(outBuf, 0, result, 0, result.size)
        return result
    }

    private fun decodeRsa2048_OaepSha256_B64(
        cipher: String,
        privateKey: ByteArray,
    ): ByteArray = kotlin.run {
        val (rsaCt) = cipher
            .split(CIPHER_DIVIDER)
            .apply {
                check(size == 1) {
                    "The cipher must consist of exactly 1 part: rsaCt. The current cipher " +
                            "contains $size parts which may cause unknown behaviour!"
                }
            }
            .map { base64 ->
                base64Service.decode(base64)
            }
        TODO()
    }

    private fun decodeRsa2048_OaepSha1_B64(
        cipher: String,
        privateKey: ByteArray,
    ): ByteArray = kotlin.run {
        val (rsaCt) = cipher
            .split(CIPHER_DIVIDER)
            .apply {
                check(size == 1) {
                    "The cipher must consist of exactly 1 part: rsaCt. The current cipher " +
                            "contains $size parts which may cause unknown behaviour!"
                }
            }
            .map { base64 ->
                base64Service.decode(base64)
            }

        val a = ASN1Sequence.fromByteArray(privateKey)
        val b = PrivateKeyInfo.getInstance(a)
        val d = PrivateKeyFactory.createKey(b) as RSAPrivateCrtKeyParameters
        val pub = RSAKeyParameters(false, d.modulus, d.publicExponent)
//        public Task<byte[]> RsaExtractPublicKeyAsync(byte[] privateKey)
//        {
//            // Have to specify some algorithm
//            var provider = AsymmetricKeyAlgorithmProvider.OpenAlgorithm(AsymmetricAlgorithm.RsaOaepSha1);
//            var cryptoKey = provider.ImportKeyPair(privateKey, CryptographicPrivateKeyBlobType.Pkcs8RawPrivateKeyInfo);
//            return Task.FromResult(cryptoKey.ExportPublicKey(CryptographicPublicKeyBlobType.X509SubjectPublicKeyInfo));
//        }

        val q1 = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(pub)

        val pubKey = RSAPublicKey.getInstance(q1.parsePublicKey())
        val p = PublicKeyFactory.createKey(q1)
        val fm = ubyteArrayOf(
            48u,
            130u,
            1u,
            34u,
            48u,
            13u,
            6u,
            9u,
            42u,
            134u,
            72u,
            134u,
            247u,
            13u,
            1u,
            1u,
            1u,
            5u,
            0u,
            3u,
            130u,
            1u,
            15u,
            0u,
        )
        // "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA5CsnpH25EPMguTAvnlW807PSM3o3RBjsCCzdNm3VNgK1Z4JSMyGnFOZq9ZZRHArV3kIYYGDZiP5kn5jw6g2XyBUbpLXw87N8jtzTENOuoUr+zQfKQX/H9w006bvENlm7LhTzL0SQbhcdzs1amqxajtzAS92YtOXizAGsYl8SieGl8OVYZNP3mbpsUpAtD/XtiDGxVo23yQ39w/6X3VYo6wYO2QY9aNCYDcLYYJ2D0y/2ocdD/QvibIVz7+4eA15p8HDWm++o9BlwZL9xZbk4x3DwWWz5Gy7hZk/tNpUgnqWFToxCRBgcMlBaI2VH6jX1ZxhUBXpEkK++n4Yz4BjRcQIDAQAB"
        // "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA5CsnpH25EPMguTAvnlW807PSM3o3RBjsCCzdNm3VNgK1Z4JSMyGnFOZq9ZZRHArV3kIYYGDZiP5kn5jw6g2XyBUbpLXw87N8jtzTENOuoUr+zQfKQX/H9w006bvENlm7LhTzL0SQbhcdzs1amqxajtzAS92YtOXizAGsYl8SieGl8OVYZNP3mbpsUpAtD/XtiDGxVo23yQ39w/6X3VYo6wYO2QY9aNCYDcLYYJ2D0y/2ocdD/QvibIVz7+4eA15p8HDWm++o9BlwZL9xZbk4x3DwWWz5Gy7hZk/tNpUgnqWFToxCRBgcMlBaI2VH6jX1ZxhUBXpEkK++n4Yz4BjRcQIDAQAB"

        val oaep = OAEPEncoding(
            RSAEngine(),
            SHA1Digest(),
            SHA1Digest(),
            null,
        )
        oaep.init(false, d)
        val f = cipherData(oaep, rsaCt)
        f
    }

    @Throws(Exception::class)
    private fun cipherData(cipher: AsymmetricBlockCipher, data: ByteArray): ByteArray {
        val result = cipher.processBlock(data, 0, data.size)
        return result
    }

    private fun decodeRsa2048_OaepSha256_HmacSha256_B64(
        cipher: String,
        encKey: ByteArray,
        macKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray = TODO()

    private fun decodeRsa2048_OaepSha1_HmacSha256_B64(
        cipher: String,
        encKey: ByteArray,
        macKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray = TODO()

    override fun encode2(
        cipherType: CipherEncryptor.Type,
        plainText: ByteArray,
        symmetricCryptoKey: SymmetricCryptoKey2?,
        asymmetricCryptoKey: AsymmetricCryptoKey?,
    ): String {
        val artifacts = when (cipherType) {
            //
            // Symmetric
            //

            CipherEncryptor.Type.AesCbc256_B64 -> {
                requireNotNull(symmetricCryptoKey) {
                    "Symmetric Crypto Key must not be null, " +
                            "for encoding $cipherType."
                }
                val cryptoKey = symmetricCryptoKey.requireAesCbc256_B64()
                encodeAesCbc256_B64(plainText, encKey = cryptoKey.encKey)
            }

            CipherEncryptor.Type.AesCbc128_HmacSha256_B64 -> {
                requireNotNull(symmetricCryptoKey) {
                    "Symmetric Crypto Key must not be null, " +
                            "for encoding $cipherType."
                }
                val cryptoKey = symmetricCryptoKey.requireAesCbc128_HmacSha256_B64()
                encodeAesCbc128_HmacSha256_B64(
                    plainText = plainText,
                    encKey = cryptoKey.encKey,
                    macKey = cryptoKey.macKey,
                )
            }

            CipherEncryptor.Type.AesCbc256_HmacSha256_B64 -> {
                requireNotNull(symmetricCryptoKey) {
                    "Symmetric Crypto Key must not be null, " +
                            "for encoding $cipherType."
                }
                val cryptoKey = symmetricCryptoKey.requireAesCbc256_HmacSha256_B64()
                encodeAesCbc256_HmacSha256_B64(
                    plainText = plainText,
                    encKey = cryptoKey.encKey,
                    macKey = cryptoKey.macKey,
                )
            }

            //
            // Asymmetric
            //

            CipherEncryptor.Type.Rsa2048_OaepSha256_B64 -> {
                requireNotNull(asymmetricCryptoKey) {
                    "Asymmetric Crypto Key must not be null, " +
                            "for encoding $cipherType."
                }

                TODO("Encoding cipher type $cipherType is not supported yet.")
            }

            CipherEncryptor.Type.Rsa2048_OaepSha1_B64 -> {
                TODO("Encoding cipher type $cipherType is not supported yet.")
            }

            CipherEncryptor.Type.Rsa2048_OaepSha256_HmacSha256_B64 -> {
                TODO("Encoding cipher type $cipherType is not supported yet.")
            }

            CipherEncryptor.Type.Rsa2048_OaepSha1_HmacSha256_B64 -> {
                TODO("Encoding cipher type $cipherType is not supported yet.")
            }
        }
        val artifactsStr = artifacts
            .joinToString(separator = CIPHER_DIVIDER) { bytes ->
                base64Service
                    .encodeToString(bytes)
            }
        return cipherType.type + "." + artifactsStr
    }

    private fun encodeAesCbc256_B64(
        plainText: ByteArray,
        iv: ByteArray = cryptoGenerator.seed(16),
        encKey: ByteArray,
    ): List<ByteArray> {
        val aes = createAesCbc(iv, encKey, forEncryption = true)
        val ct = cipherData(aes, plainText)
        return listOf(iv, ct)
    }

    private fun encodeAesCbc128_HmacSha256_B64(
        plainText: ByteArray,
        iv: ByteArray = cryptoGenerator.seed(16),
        encKey: ByteArray,
        macKey: ByteArray,
    ): List<ByteArray> {
        require(encKey.size == 16) {
            "Aes-Cbc-128 requires a 16-byte key!"
        }
        return encodeAesCbc256_HmacSha256_B64(
            plainText = plainText,
            iv = iv,
            encKey = encKey,
            macKey = macKey,
        )
    }

    private fun encodeAesCbc256_HmacSha256_B64(
        plainText: ByteArray,
        iv: ByteArray = cryptoGenerator.seed(16),
        encKey: ByteArray,
        macKey: ByteArray,
    ): List<ByteArray> {
        val aes = createAesCbc(iv, encKey, forEncryption = true)
        val ct = cipherData(aes, plainText)
        val mac = cryptoGenerator.hmacSha256(macKey, iv + ct)
        return listOf(iv, ct, mac)
    }

    private fun encodeRsa2048_OaepSha256_B64(
        plainText: ByteArray,
        encKey: ByteArray,
        publicKey: ByteArray,
    ): List<ByteArray> = TODO()

    private fun encodeRsa2048_OaepSha1_B64(
        plainText: ByteArray,
        encKey: ByteArray,
        publicKey: ByteArray,
    ): List<ByteArray> = TODO()

    private fun encodeRsa2048_OaepSha256_HmacSha256_B64(
        plainText: ByteArray,
        encKey: ByteArray,
        publicKey: ByteArray,
    ): List<ByteArray> = TODO()

    private fun encodeRsa2048_OaepSha1_HmacSha256_B64(
        plainText: ByteArray,
        encKey: ByteArray,
        publicKey: ByteArray,
    ): List<ByteArray> = TODO()
}
