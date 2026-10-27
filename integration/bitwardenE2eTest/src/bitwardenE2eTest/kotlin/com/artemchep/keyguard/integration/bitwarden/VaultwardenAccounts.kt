package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID

/**
 * Seeds Vaultwarden over the Bitwarden client API, doing the client-side
 * cryptography a Bitwarden client does.
 */
// The crypto helpers belong with the seeding: tests reach both through `accounts`.
@Suppress("TooManyFunctions")
internal class VaultwardenAccounts(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val cryptoGenerator: CryptoGenerator,
    private val cipherEncryptor: CipherEncryptor,
    private val base64Service: Base64Service,
) {
    private val deviceIdentifier = UUID.randomUUID().toString()

    suspend fun register(
        email: String = uniqueEmail(),
        password: String = DEFAULT_PASSWORD,
        name: String = DEFAULT_NAME,
        kdf: TestKdf = TestKdf.Pbkdf2(),
    ): TestAccount {
        val masterKey = masterKey(email = email, password = password, kdf = kdf)
        val masterPasswordHash = masterPasswordHash(masterKey = masterKey, password = password)
        val userKey = cryptoGenerator.seed(length = KEY_LENGTH * 2)
        val keyPair = newRsaKeyPair()
        val account = TestAccount(
            email = email,
            password = password,
            kdf = kdf,
            masterKey = masterKey,
            masterPasswordHash = masterPasswordHash,
            userKey = userKey,
            privateKey = keyPair.private.encoded,
        )

        val verificationToken = requestRegistrationToken(email = email, name = name)
        httpClient
            .post("$baseUrl/identity/accounts/register/finish") {
                setJsonBody(
                    buildJsonObject {
                        put("email", email)
                        put("emailVerificationToken", verificationToken)
                        put("name", name)
                        put("masterPasswordHash", masterPasswordHash)
                        put("masterPasswordHint", JsonNull)
                        putKdf(kdf)
                        put("key", encrypt(stretch(masterKey), userKey))
                        putJsonObject("keys") {
                            put("publicKey", base64Service.encodeToString(keyPair.public.encoded))
                            put("encryptedPrivateKey", encrypt(userKey, keyPair.private.encoded))
                        }
                    },
                )
            }
            .requireJson("Register $email")
        return account
    }

    /** What a client sends as its password: PBKDF2 of the master key, salted with the password. */
    fun masterPasswordHash(masterKey: ByteArray, password: String): String = cryptoGenerator
        .pbkdf2(
            seed = masterKey,
            salt = password.encodeToByteArray(),
            iterations = 1,
            length = KEY_LENGTH,
        )
        .let(base64Service::encodeToString)

    /** Mirrors the master key derivation of Keyguard's `AuthApi`. */
    private fun masterKey(email: String, password: String, kdf: TestKdf): ByteArray {
        val salt = email.trim().lowercase().encodeToByteArray()
        return when (kdf) {
            is TestKdf.Pbkdf2 -> cryptoGenerator.pbkdf2(
                seed = password.encodeToByteArray(),
                salt = salt,
                iterations = kdf.iterations,
                length = KEY_LENGTH,
            )

            is TestKdf.Argon2id -> cryptoGenerator.argon2(
                mode = Argon2Mode.ARGON2_ID,
                seed = password.encodeToByteArray(),
                salt = cryptoGenerator.hashSha256(salt),
                iterations = kdf.iterations,
                memoryKb = kdf.memoryMib * 1024,
                parallelism = kdf.parallelism,
            )
        }
    }

    /** Without SMTP the server skips the mail and returns the email verification token. */
    private suspend fun requestRegistrationToken(email: String, name: String): String = httpClient
        .post("$baseUrl/identity/accounts/register/send-verification-email") {
            // Without it the token comes back as text/plain.
            accept(ContentType.Application.Json)
            setJsonBody(
                buildJsonObject {
                    put("email", email)
                    put("name", name)
                    put("receiveMarketingEmails", false)
                },
            )
        }
        .requireJson("Request a registration token")
        .jsonPrimitive
        .content

    /**
     * A password login. Pass [deviceIdentifier] from `VaultwardenFixture.deviceId()` to get
     * a remember token that Keyguard's own login can use.
     */
    suspend fun login(
        account: TestAccount,
        twoFactor: RawTwoFactor? = null,
        deviceIdentifier: String = this.deviceIdentifier,
    ): RawLogin {
        val response = httpClient
            .submitForm(
                url = "$baseUrl/identity/connect/token",
                formParameters = parameters {
                    append("grant_type", "password")
                    append("username", account.email)
                    append("password", account.masterPasswordHash)
                    append("scope", "api offline_access")
                    append("client_id", "desktop")
                    append("deviceType", DEVICE_TYPE_LINUX_DESKTOP)
                    append("deviceIdentifier", deviceIdentifier)
                    append("deviceName", "keyguard-e2e")
                    if (twoFactor != null) {
                        append("twoFactorProvider", twoFactor.provider.toString())
                        append("twoFactorToken", twoFactor.token)
                        append("twoFactorRemember", if (twoFactor.remember) "1" else "0")
                    }
                },
            )
            .requireJson("Log in as ${account.email}")
            .jsonObject
        return RawLogin(
            accessToken = response.string("access_token"),
            key = response.string("Key"),
            twoFactorToken = response.stringOrNull("TwoFactorToken"),
        )
    }

    suspend fun sync(accessToken: String): JsonObject = httpClient
        .get("$baseUrl/api/sync") {
            bearerAuth(accessToken)
        }
        .requireJson("Sync")
        .jsonObject

    /** The SPKI DER public key of a user. */
    suspend fun publicKey(accessToken: String, userId: String): ByteArray = httpClient
        .get("$baseUrl/api/users/$userId/public-key") {
            bearerAuth(accessToken)
        }
        .requireJson("Get the public key of $userId")
        .jsonObject
        .string("publicKey")
        .let(base64Service::decode)

    /** Returns the id of a new folder whose name is encrypted with the user key. */
    suspend fun createFolder(
        accessToken: String,
        account: TestAccount,
        name: String,
    ): String = httpClient
        .post("$baseUrl/api/folders") {
            bearerAuth(accessToken)
            setJsonBody(buildJsonObject { put("name", encrypt(account, name)) })
        }
        .requireJson("Create a folder")
        .jsonObject
        .string("id")

    /**
     * Returns the id of a new login cipher whose fields are encrypted with the user key.
     * [totp] is a base32 secret or an `otpauth://` URI.
     */
    suspend fun createLoginCipher(
        accessToken: String,
        account: TestAccount,
        name: String,
        username: String,
        password: String,
        uri: String? = null,
        notes: String? = null,
        totp: String? = null,
        folderId: String? = null,
    ): String = createCipher(accessToken, account, CIPHER_TYPE_LOGIN, name, notes, folderId) {
        putJsonObject("login") {
            put("username", encrypt(account, username))
            put("password", encrypt(account, password))
            if (uri != null) {
                putJsonArray("uris") {
                    addJsonObject {
                        put("uri", encrypt(account, uri))
                        put("match", JsonNull)
                    }
                }
            } else {
                put("uris", JsonNull)
            }
            put("totp", totp?.let { encrypt(account, it) })
        }
    }

    /** Returns the id of a new secure note whose fields are encrypted with the user key. */
    suspend fun createSecureNote(
        accessToken: String,
        account: TestAccount,
        name: String,
        text: String,
        folderId: String? = null,
    ): String = createCipher(accessToken, account, CIPHER_TYPE_SECURE_NOTE, name, text, folderId) {
        putJsonObject("secureNote") {
            put("type", 0)
        }
    }

    private suspend fun createCipher(
        accessToken: String,
        account: TestAccount,
        type: Int,
        name: String,
        notes: String? = null,
        folderId: String? = null,
        content: JsonObjectBuilder.() -> Unit,
    ): String = httpClient
        .post("$baseUrl/api/ciphers") {
            bearerAuth(accessToken)
            setJsonBody(
                buildJsonObject {
                    put("type", type)
                    put("name", encrypt(account, name))
                    put("notes", notes?.let { encrypt(account, it) })
                    put("favorite", false)
                    put("folderId", folderId)
                    put("organizationId", JsonNull)
                    content()
                    put("reprompt", 0)
                    // Vaultwarden rejects a cipher without the id of the user whose key encrypts it.
                    put("encryptedFor", userId(accessToken))
                },
            )
        }
        .requireJson("Create a cipher")
        .jsonObject
        .string("id")

    /** Deletes the account through the admin API, which the server opens without a token. */
    suspend fun deleteUser(email: String) {
        val id = httpClient
            .get("$baseUrl/admin/users/by-mail/${email.encodeURLPathPart()}")
            .requireJson("Find $email")
            .jsonObject
            .string("id")
        httpClient
            .post("$baseUrl/admin/users/$id/delete") {
                // The route only matches a JSON request.
                setJsonBody(JsonObject(emptyMap()))
            }
            .requireSuccess("Delete $email")
    }

    /** An EncString of type 2 (AES-CBC-256 with HMAC-SHA256), encrypted with the user key. */
    fun encrypt(account: TestAccount, text: String): String = encrypt(account.userKey, text)

    /** An EncString of type 2 under any 64-byte key. */
    fun encrypt(key: ByteArray, text: String): String = encrypt(key, text.encodeToByteArray())

    /** An EncString of type 2 under any 64-byte key. */
    fun encrypt(key: ByteArray, plainText: ByteArray): String = cipherEncryptor.encode2(
        cipherType = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
        plainText = plainText,
        symmetricCryptoKey = SymmetricCryptoKey2(key),
    )

    fun decrypt(key: ByteArray, cipherText: String): ByteArray = cipherEncryptor
        .decode2(
            cipher = cipherText,
            symmetricCryptoKey = SymmetricCryptoKey2(key),
        )
        .data

    /** The key that wraps the user key: HKDF-Expand of the master key, as Bitwarden does it. */
    fun stretch(masterKey: ByteArray): ByteArray {
        val encKey = cryptoGenerator.hkdf(seed = masterKey, info = "enc".encodeToByteArray())
        val macKey = cryptoGenerator.hkdf(seed = masterKey, info = "mac".encodeToByteArray())
        return encKey + macKey
    }

    /** The user id: the `sub` claim of the access token. */
    fun userId(accessToken: String): String {
        val payload = accessToken.split('.')[1]
        return Json.parseToJsonElement(Base64.getUrlDecoder().decode(payload).decodeToString())
            .jsonObject
            .string("sub")
    }

    companion object {
        const val DEFAULT_PASSWORD = "correct horse battery staple"
        const val DEFAULT_NAME = "Keyguard E2E"

        private const val KEY_LENGTH = 32
        private const val CIPHER_TYPE_LOGIN = 1
        private const val CIPHER_TYPE_SECURE_NOTE = 2
        private const val DEVICE_TYPE_LINUX_DESKTOP = "8"

        fun uniqueEmail(): String = "user-${UUID.randomUUID().toString().take(8)}@keyguard.test"
    }
}

/** An RSA-2048 key pair, as Bitwarden clients create for users and organizations. */
internal fun newRsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA")
    .apply { initialize(RSA_KEY_SIZE) }
    .generateKeyPair()

private const val RSA_KEY_SIZE = 2048
