package com.artemchep.keyguard.common.service.vault.impl

import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.FingerprintBiometric
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.FingerprintYubiKey
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.SecureKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.getObject
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class FingerprintRepositoryImpl(
    private val store: KeyValueStore,
    private val json: Json,
    private val base64Service: Base64Service,
) : FingerprintReadWriteRepository {
    private val dataPref = store.getObject(
        key = "data",
        defaultValue = null,
        serialize = { tokens ->
            if (tokens == null) {
                return@getObject ""
            }

            val entity = kotlin.run {
                // Convert the model to the entity
                val masterHashBase64 = base64Service.encodeToString(tokens.master.hash.byteArray)
                val masterSaltBase64 = base64Service.encodeToString(tokens.master.salt.byteArray)
                val master = FingerprintEntity.Master(
                    hashBase64 = masterHashBase64,
                    saltBase64 = masterSaltBase64,
                )
                val biometric = tokens.biometric?.let {
                    val ivBase64 = base64Service.encodeToString(it.iv)
                    val encryptedMasterKeyBase64 = base64Service
                        .encodeToString(it.encryptedMasterKey)
                    FingerprintEntity.Biometric(
                        ivBase64 = ivBase64,
                        encryptedMasterKeyBase64 = encryptedMasterKeyBase64,
                    )
                }
                val yubiKey = tokens.yubiKey?.let {
                    FingerprintEntity.YubiKey(
                        slot = it.slot,
                        challengeBase64 = base64Service.encodeToString(it.challenge),
                        hkdfSaltBase64 = base64Service.encodeToString(it.hkdfSalt),
                        encryptedMasterKey = it.encryptedMasterKey,
                        cipherType = it.cipherType.type,
                    )
                }
                FingerprintEntity(
                    version = tokens.version.raw,
                    master = master,
                    biometric = biometric,
                    yubiKey = yubiKey,
                )
            }
            json.encodeToString(entity)
        },
        deserialize = { data ->
            if (data.isBlank()) {
                return@getObject null
            }

            // If any exception happens, we just assume that the data
            // is not present at all. Note: this implicitly clears all of
            // the local data of the user.
            kotlin.runCatching {
                parseFingerprintStringOrNull(
                    json = json,
                    base64Service = base64Service,
                    data = data,
                )
            }.getOrNull()
        },
    )

    init {
        // Double check that we store the
        // fingerprint in the secure store.
        require(store is SecureKeyValueStore) {
            "Fingerprint repository should be using a secure store. " +
                    "Current use of ${store::class.simpleName} is probably a mistake."
        }
    }

    constructor(directDI: DirectDI) : this(
        store = directDI.instance<Files, KeyValueStore>(arg = Files.FINGERPRINT),
        json = directDI.instance(),
        base64Service = directDI.instance(),
    )

    override fun put(key: Fingerprint?) = dataPref.setAndCommit(key)

    override fun get(): Flow<Fingerprint?> = dataPref
}

private fun parseFingerprintStringOrNull(
    json: Json,
    base64Service: Base64Service,
    data: String,
): Fingerprint? {
    // Check whether the data string is using a new or an old format
    // by checking a first symbol.
    val isJsonObject = data.firstOrNull() == '{'
    return if (isJsonObject) {
        parseFingerprintJsonStringOrNull(
            json = json,
            base64Service = base64Service,
            data = data,
        )
    } else {
        parseFingerprintRawStringOrNull(
            base64Service = base64Service,
            data = data,
        )
    }
}

private fun parseFingerprintJsonStringOrNull(
    json: Json,
    base64Service: Base64Service,
    data: String,
): Fingerprint {
    val entity = json.decodeFromString<FingerprintEntity>(data)
    val version = MasterKdfVersion.fromRaw(entity.version)

    // Convert the entity back to a domain model
    val masterHashBytes = base64Service.decode(entity.master.hashBase64)
    val masterSaltBytes = base64Service.decode(entity.master.saltBase64)
    val master = FingerprintPassword(
        hash = MasterPasswordHash(
            version = version,
            byteArray = masterHashBytes,
        ),
        salt = MasterPasswordSalt(masterSaltBytes),
    )
    val biometric = entity.biometric?.let {
        val iv = base64Service.decode(it.ivBase64)
        val encryptedMasterKey = base64Service.decode(it.encryptedMasterKeyBase64)
        FingerprintBiometric(
            iv = iv,
            encryptedMasterKey = encryptedMasterKey,
        )
    }
    val yubiKey = entity.yubiKey?.let {
        val cipherType = CipherEncryptor.Type.entries
            .firstOrNull { entry -> entry.type == it.cipherType }
            ?: CipherEncryptor.Type.AesCbc256_HmacSha256_B64
        FingerprintYubiKey(
            slot = it.slot,
            challenge = base64Service.decode(it.challengeBase64),
            hkdfSalt = base64Service.decode(it.hkdfSaltBase64),
            encryptedMasterKey = it.encryptedMasterKey,
            cipherType = cipherType,
        )
    }
    return Fingerprint(
        version = version,
        master = master,
        biometric = biometric,
        yubiKey = yubiKey,
    )
}

private fun parseFingerprintRawStringOrNull(
    base64Service: Base64Service,
    data: String,
): Fingerprint? {
    val list = data
        .split("|")
        .map(base64Service::decode)
    if (list.size != 2 && list.size != 4) {
        return null
    }

    val masterHash = list[0]
    val masterSalt = list[1]
    val biometric = if (list.size == 4) {
        val biometricEncryptedMasterKey = list[2]
        val biometricIv = list[3]
        FingerprintBiometric(
            iv = biometricIv,
            encryptedMasterKey = biometricEncryptedMasterKey,
        )
    } else {
        null
    }
    val version = MasterKdfVersion.V0 // old format doesn't have a version field
    return Fingerprint(
        version = version,
        master = FingerprintPassword(
            hash = MasterPasswordHash(
                version = version,
                byteArray = masterHash,
            ),
            salt = MasterPasswordSalt(masterSalt),
        ),
        biometric = biometric,
        yubiKey = null,
    )
}

@Serializable
data class FingerprintEntity(
    val version: Int,
    val master: Master,
    val biometric: Biometric?,
    val yubiKey: YubiKey? = null,
) {
    @Serializable
    data class Master(
        val hashBase64: String,
        val saltBase64: String,
    )

    @Serializable
    data class Biometric(
        val ivBase64: String,
        val encryptedMasterKeyBase64: String,
    )

    @Serializable
    data class YubiKey(
        val slot: Int,
        val challengeBase64: String,
        val hkdfSaltBase64: String,
        val encryptedMasterKey: String,
        val cipherType: String = CipherEncryptor.Type.AesCbc256_HmacSha256_B64.type,
    )
}
