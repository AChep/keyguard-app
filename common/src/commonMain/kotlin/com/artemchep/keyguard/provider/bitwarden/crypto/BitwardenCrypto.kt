package com.artemchep.keyguard.provider.bitwarden.crypto

import arrow.core.partially2
import arrow.core.partially3
import com.artemchep.keyguard.common.exception.DecodeException
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service

private typealias Decoder = (String) -> DecodeResult

private typealias Encoder = (CipherEncryptor.Type, ByteArray) -> String

sealed interface BitwardenCrKey {
    data object AuthToken : BitwardenCrKey

    //
    // User / Organization
    //

    data object UserToken : BitwardenCrKey

    data class OrganizationToken(
        val id: String,
    ) : BitwardenCrKey

    data class SendToken(
        val id: String,
    ) : BitwardenCrKey
}

interface BitwardenCr {
    val base64Service: Base64Service

    fun decoder(
        key: BitwardenCrKey,
    ): Decoder

    fun encoder(
        key: BitwardenCrKey,
    ): Encoder

    fun cta(
        env: BitwardenCrCta.BitwardenCrCtaEnv,
        mode: BitwardenCrCta.Mode,
    ): BitwardenCrCta
}

class BitwardenCrCta(
    private val crypto: BitwardenCr,
    val env: BitwardenCrCtaEnv,
    val mode: Mode,
) : BitwardenCr by crypto {
    data class BitwardenCrCtaEnv(
        val key: BitwardenCrKey,
        /** An encryption type */
        val encryptionType: CipherEncryptor.Type,
    )

    fun withEnv(env: BitwardenCrCtaEnv) = BitwardenCrCta(
        crypto = crypto,
        env = env,
        mode = mode,
    )

    enum class Mode {
        ENCRYPT,
        DECRYPT,
    }
}

inline fun <T> BitwardenCrCta.whatIf(
    isEncrypt: BitwardenCrCta.() -> T,
    isDecrypt: BitwardenCrCta.() -> T,
): T = when (mode) {
    BitwardenCrCta.Mode.DECRYPT -> isDecrypt()
    BitwardenCrCta.Mode.ENCRYPT -> isEncrypt()
}

@JvmName("transformString")
fun BitwardenCrCta.transformString(
    value: String,
): String = whatIf(
    isEncrypt = {
        val encryptionType = env.encryptionType
        val data = value.toByteArray()
        encoder(env.key).invoke(encryptionType, data)
    },
    isDecrypt = {
        // This should never happen, but just in case to not
        // fail the decryption process we fall back to an empty string.
        if (value.isEmpty()) {
            return@whatIf value
        }
        val data = decoder(env.key).invoke(value).data
        String(data)
    },
)

@JvmName("transformStringNullable")
fun BitwardenCrCta.transformString(
    value: String?,
): String? = value?.let { transformString(it) }

fun BitwardenCrCta.transformBase64(
    value: String,
): String = whatIf(
    isEncrypt = {
        val encryptionType = env.encryptionType
        val data = base64Service.decode(value)
        encoder(env.key).invoke(encryptionType, data)
    },
    isDecrypt = {
        val data = decoder(env.key).invoke(value).data
        base64Service.encodeToString(data)
    },
)

interface BitwardenCrFactoryScope : BitwardenCr {
    val cipherEncryptor: CipherEncryptor
    val cryptoGenerator: CryptoGenerator

    fun appendDecoder(
        key: BitwardenCrKey,
        decoder: Decoder,
    )

    fun appendEncoder(
        key: BitwardenCrKey,
        encoder: Encoder,
    )

    fun build(): BitwardenCr
}

class BitwardenCrImpl(
    override val cipherEncryptor: CipherEncryptor,
    override val cryptoGenerator: CryptoGenerator,
    override val base64Service: Base64Service,
) : BitwardenCrFactoryScope {
    private val decoders: MutableMap<BitwardenCrKey, Decoder> = mutableMapOf()

    private val encoders: MutableMap<BitwardenCrKey, Encoder> = mutableMapOf()

    override fun appendDecoder(
        key: BitwardenCrKey,
        decoder: Decoder,
    ) {
        if (key in decoders) error("The decoder with key '$key' is already defined!")
        decoders[key] = decoder
    }

    override fun appendEncoder(
        key: BitwardenCrKey,
        encoder: Encoder,
    ) {
        if (key in encoders) error("The encoder with key '$key' is already defined!")
        encoders[key] = encoder
    }

    override fun build(): BitwardenCr = this

    //
    // Decoder
    //

    override fun decoder(key: BitwardenCrKey): Decoder = decoders.getValue(key)

    override fun encoder(key: BitwardenCrKey): Encoder = encoders.getValue(key)

    override fun cta(
        env: BitwardenCrCta.BitwardenCrCtaEnv,
        mode: BitwardenCrCta.Mode,
    ): BitwardenCrCta = BitwardenCrCta(
        crypto = this,
        env = env,
        mode = mode,
    )
}

private fun Decoder.withExceptionHandling(
    key: BitwardenCrKey,
    symmetricCryptoKey: SymmetricCryptoKey2? = null,
    asymmetricCryptoKey: AsymmetricCryptoKey? = null,
): Decoder =
    { cipherText ->
        val result = kotlin.runCatching {
            this(cipherText)
        }.getOrElse { e ->
            val type = cipherText.substringBefore('.')
                // If the cipher text for some reason doesn't have a
                // dot separated type, then take only first N symbols
                // to avoid showing the whole cipher text in the error
                // message.
                .take(8)
            val info = listOfNotNull(
                symmetricCryptoKey?.let { "symmetric key is ${it.data.size}b long" },
                asymmetricCryptoKey?.let { "asymmetric key is ${it.privateKey.size}b long" },
            ).joinToString()
            val cause = e.localizedMessage ?: e.message
            val msg = "Failed to decode a cipher-text with the type '$type': $key, $info. " +
                    "$cause"
            throw DecodeException(msg, e)
        }
        result
    }

fun BitwardenCrFactoryScope.appendUserToken(
    encKey: ByteArray,
    macKey: ByteArray,
) {
    val symmetricCryptoKey = SymmetricCryptoKey2(
        data = encKey + macKey,
    )
    val decoder = cipherEncryptor::decode2
        .partially2(symmetricCryptoKey)
        .partially2(null)
        .withExceptionHandling(BitwardenCrKey.AuthToken)
    val encoder = cipherEncryptor::encode2
        .partially3(symmetricCryptoKey)
        .partially3(null)
    appendDecoder(BitwardenCrKey.AuthToken, decoder)
    appendEncoder(BitwardenCrKey.AuthToken, encoder)
}

fun BitwardenCrFactoryScope.appendProfileToken(
    keyCipherText: String,
    privateKeyCipherText: String,
) {
    val symmetricCryptoKey = decoder(BitwardenCrKey.AuthToken)(keyCipherText)
        .data
        .let(CryptoKey::decodeSymmetricOrThrow)
    val asymmetricCryptoKey = kotlin.run {
        // RSA encryption requires to include the private
        // key into the decoder.
        cipherEncryptor
            .decode2(
                cipher = privateKeyCipherText,
                symmetricCryptoKey = symmetricCryptoKey,
            )
            .data
            .let(CryptoKey::decodeAsymmetricOrThrow)
    }
    val decoder = cipherEncryptor::decode2
        .partially2(symmetricCryptoKey)
        .partially2(asymmetricCryptoKey)
        .withExceptionHandling(
            BitwardenCrKey.UserToken,
            symmetricCryptoKey = symmetricCryptoKey,
            asymmetricCryptoKey = asymmetricCryptoKey,
        )
    val encoder = cipherEncryptor::encode2
        .partially3(symmetricCryptoKey)
        .partially3(asymmetricCryptoKey)
    appendDecoder(BitwardenCrKey.UserToken, decoder)
    appendEncoder(BitwardenCrKey.UserToken, encoder)
}

fun BitwardenCrFactoryScope.appendProfileToken2(
    keyData: ByteArray,
    privateKey: ByteArray,
) {
    val symmetricCryptoKey = CryptoKey.decodeSymmetricOrThrow(keyData)
    val asymmetricCryptoKey = CryptoKey.decodeAsymmetricOrThrow(privateKey)
    val decoder = cipherEncryptor::decode2
        .partially2(symmetricCryptoKey)
        .partially2(asymmetricCryptoKey)
        .withExceptionHandling(
            BitwardenCrKey.UserToken,
            symmetricCryptoKey = symmetricCryptoKey,
            asymmetricCryptoKey = asymmetricCryptoKey,
        )
    val encoder = cipherEncryptor::encode2
        .partially3(symmetricCryptoKey)
        .partially3(asymmetricCryptoKey)
    appendDecoder(BitwardenCrKey.UserToken, decoder)
    appendEncoder(BitwardenCrKey.UserToken, encoder)
}

fun BitwardenCrFactoryScope.appendOrganizationToken(
    id: String,
    keyCipherText: String,
) {
    val symmetricCryptoKey = decoder(BitwardenCrKey.UserToken)(keyCipherText)
        .data
        .let(CryptoKey::decodeSymmetricOrThrow)
    val key = BitwardenCrKey.OrganizationToken(id)
    val decoder = cipherEncryptor::decode2
        .partially2(symmetricCryptoKey)
        .partially2(null)
        .withExceptionHandling(
            key,
            symmetricCryptoKey = symmetricCryptoKey,
        )
    val encoder = cipherEncryptor::encode2
        .partially3(symmetricCryptoKey)
        .partially3(null)
    appendDecoder(key, decoder)
    appendEncoder(key, encoder)
}

fun BitwardenCrFactoryScope.appendSendToken(
    id: String,
    keyCipherText: String,
) {
    val symmetricCryptoKey = decoder(BitwardenCrKey.UserToken)(keyCipherText)
        .data
        .let { keyMaterial ->
            val key = cryptoGenerator.hkdf(
                seed = keyMaterial,
                salt = "bitwarden-send".toByteArray(),
                info = "send".toByteArray(),
                length = 64,
            )
            key
        }
        .let(CryptoKey::decodeSymmetricOrThrow)
    val key = BitwardenCrKey.SendToken(id)
    val decoder = cipherEncryptor::decode2
        .partially2(symmetricCryptoKey)
        .partially2(null)
        .withExceptionHandling(
            key,
            symmetricCryptoKey = symmetricCryptoKey,
        )
    val encoder = cipherEncryptor::encode2
        .partially3(symmetricCryptoKey)
        .partially3(null)
    appendDecoder(key, decoder)
    appendEncoder(key, encoder)
}

fun BitwardenCrFactoryScope.appendOrganizationToken2(
    id: String,
    keyData: ByteArray,
) {
    val symmetricCryptoKey = CryptoKey.decodeSymmetricOrThrow(keyData)
    val key = BitwardenCrKey.OrganizationToken(id)
    val decoder = cipherEncryptor::decode2
        .partially2(symmetricCryptoKey)
        .partially2(null)
        .withExceptionHandling(
            key,
            symmetricCryptoKey = symmetricCryptoKey,
        )
    val encoder = cipherEncryptor::encode2
        .partially3(symmetricCryptoKey)
        .partially3(null)
    appendDecoder(key, decoder)
    appendEncoder(key, encoder)
}
