package com.artemchep.keyguard.provider.keyguard

import com.artemchep.keyguard.common.exception.DecodeException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.impl.GenerateMasterHashUtils
import com.artemchep.keyguard.common.util.useAndClear
import com.artemchep.keyguard.core.store.bitwarden.P2pAuthority
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeEd25519KeyPair
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import kotlinx.coroutines.Dispatchers

/**
 * Asks the user for the master password and returns it once it matches the one of the
 * vault, or `null` if they cancelled. Changing who is in a sync group needs it, see
 * [KeyguardAuthorityKeys].
 */
typealias KeyguardPasswordPrompt = suspend () -> String?

/**
 * An open authority key: the sealed [authority] and its 32-byte Ed25519 [seed]. Hold it only
 * for one operation, then [close] it to wipe the seed.
 */
class KeyguardAuthorityKey(
    val authority: P2pAuthority,
    val seed: ByteArray,
) : AutoCloseable {
    override fun close() {
        seed.fill(0)
    }

    /**
     * Checks that this is still the authority key of the device of the [membership].
     *
     * @throws IllegalStateException if the key changed.
     */
    internal fun checkCurrent(membership: P2pMembership) {
        check(membership.device.authority == authority) {
            "The authority key of this device changed."
        }
    }
}

/**
 * Creates, opens and re-seals the authority keys of this device, see [P2pAuthority]. The
 * sealing key is derived from the master password with the key derivation of the master
 * key, under a salt of its own, so the master key never opens it.
 */
class KeyguardAuthorityKeys(
    private val cryptoGenerator: CryptoGenerator,
    private val cipherEncryptor: CipherEncryptor,
    private val generateKeyPair: () -> NativeEd25519KeyPair = NativeCryptoPrimitives::ed25519Generate,
) {
    companion object {
        private const val TAG = "KeyguardAuthorityKeys"

        private const val OPEN_OWN_FAILED = "The master password does not open the authority key of this device."

        private const val SALT_SIZE = 32
        private const val SEED_SIZE = 32
        private const val SEALING_KEY_SIZE = 64
        private const val HKDF_INFO = "keyguard-p2p-authority/v1"
    }

    private val kdf = GenerateMasterHashUtils(cryptoGenerator)

    /** Creates a new authority key sealed under the master [password]. */
    fun create(password: String): IO<KeyguardAuthorityKey> = ioEffect(Dispatchers.Default) {
        val pair = generateKeyPair()
        val authority = seal(
            publicKeyBase64 = P2pEncoding.base64Url(pair.publicKey),
            seed = pair.privateKey,
            password = password,
        )
        KeyguardAuthorityKey(authority, pair.privateKey)
    }

    /** Opens [authority] with the master [password], or returns `null` if it does not open. */
    fun open(authority: P2pAuthority, password: String): IO<KeyguardAuthorityKey?> = ioEffect(Dispatchers.Default) {
        val salt = P2pEncoding.base64UrlOrNull(authority.saltBase64)
        val seed = salt?.let {
            withSealingKey(password, it, MasterKdfVersion.fromRaw(authority.kdfVersion)) { key ->
                try {
                    cipherEncryptor.decode2(authority.sealedSeed, key).data
                } catch (expected: DecodeException) {
                    null
                }
            }
        }
        if (seed != null && seed.size != SEED_SIZE) seed.fill(0)
        seed?.takeIf { it.size == SEED_SIZE }?.let { KeyguardAuthorityKey(authority, it) }
    }

    /**
     * Opens the authority key of this device in the group [groupId] of the [db] with the
     * master [password]. The key derivation is slow, so it runs apart from the database:
     * once the caller takes it again, [KeyguardAuthorityKey.checkCurrent] checks that the
     * key did not change meanwhile.
     *
     * @throws IllegalArgumentException if there is no such group.
     * @throws IllegalStateException if the device has no authority key, or the password
     *   does not open it.
     */
    internal suspend fun openOwn(
        db: VaultDatabaseManager,
        groupId: P2pGroupId,
        password: String,
    ): KeyguardAuthorityKey = checkNotNull(openOwnOrNull(db, groupId, password)) {
        OPEN_OWN_FAILED
    }

    /**
     * Like [openOwn], but returns `null` if the device has no authority key, or there is no
     * [password] to open it with.
     */
    internal suspend fun openOwnOrNull(
        db: VaultDatabaseManager,
        groupId: P2pGroupId,
        password: String?,
    ): KeyguardAuthorityKey? {
        val sealed = db
            .mutate(TAG) { database -> database.requireP2pMembership(groupId).device.authority }
            .bind()
        if (sealed == null || password == null) return null
        return checkNotNull(open(sealed, password).bind()) {
            OPEN_OWN_FAILED
        }
    }

    /**
     * Returns [authority] sealed under the [newPassword] instead of the [oldPassword], with
     * the same key, or `null` if the old password does not open it.
     */
    fun reseal(
        authority: P2pAuthority,
        oldPassword: String,
        newPassword: String,
    ): IO<P2pAuthority?> = ioEffect(Dispatchers.Default) {
        open(authority, oldPassword).bind()?.use { key ->
            seal(authority.publicKeyBase64, key.seed, newPassword)
        }
    }

    /** Seals the [seed] of the key [publicKeyBase64] under the master [password], with a new salt. */
    private suspend fun seal(
        publicKeyBase64: String,
        seed: ByteArray,
        password: String,
    ): P2pAuthority {
        val salt = cryptoGenerator.seed(SALT_SIZE)
        val version = MasterKdfVersion.LATEST
        val sealed = withSealingKey(password, salt, version) { key ->
            cipherEncryptor.encode2(CipherEncryptor.Type.AesCbc256_HmacSha256_B64, seed, key)
        }
        return P2pAuthority(
            publicKeyBase64 = publicKeyBase64,
            saltBase64 = P2pEncoding.base64Url(salt),
            kdfVersion = version.raw,
            sealedSeed = sealed,
        )
    }

    private suspend fun <T> withSealingKey(
        password: String,
        salt: ByteArray,
        version: MasterKdfVersion,
        block: (SymmetricCryptoKey2) -> T,
    ): T {
        val bytes = kdf.hash(MasterPassword.of(password).byteArray, salt, version).bind()
            .useAndClear { secret ->
                cryptoGenerator.hkdf(
                    seed = secret,
                    salt = salt,
                    info = HKDF_INFO.encodeToByteArray(),
                    length = SEALING_KEY_SIZE,
                )
            }
        val key = SymmetricCryptoKey2(bytes)
        val parts = key.requireAesCbc256_HmacSha256_B64()
        return try {
            block(key)
        } finally {
            bytes.fill(0)
            parts.encKey.fill(0)
            parts.macKey.fill(0)
        }
    }
}
