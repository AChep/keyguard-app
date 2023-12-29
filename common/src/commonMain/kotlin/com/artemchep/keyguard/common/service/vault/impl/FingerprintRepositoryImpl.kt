package com.artemchep.keyguard.common.service.vault.impl

import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.FingerprintBiometric
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.SecureKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.getObject
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import kotlinx.coroutines.flow.Flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class FingerprintRepositoryImpl(
    private val store: KeyValueStore,
    private val base64Service: Base64Service,
) : FingerprintReadWriteRepository {
    private val dataPref = store.getObject(
        key = "data",
        defaultValue = null,
        serialize = { tokens ->
            if (tokens == null) {
                return@getObject ""
            }

            listOfNotNull(
                tokens.master.hash.byteArray,
                tokens.master.salt.byteArray,
                // The biometric token might be null, if the
                // user did not enable biometric login.
                tokens.biometric?.encryptedMasterKey,
                tokens.biometric?.iv,
            )
                .map(base64Service::encode)
                .joinToString(separator = "|") { String(it) }
        },
        deserialize = { data ->
            // If any exception happens, we just assume that the data
            // is not present at all. Note: this implicitly clears all of
            // the local data of the user.
            kotlin.runCatching {
                val list = data
                    .split("|")
                    .map(base64Service::decode)
                if (list.size != 2 && list.size != 4) {
                    return@runCatching null
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
                Fingerprint(
                    master = FingerprintPassword(
                        hash = MasterPasswordHash(masterHash),
                        salt = MasterPasswordSalt(masterSalt),
                    ),
                    biometric = biometric,
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
        base64Service = directDI.instance(),
    )

    override fun put(key: Fingerprint?) = dataPref.setAndCommit(key)

    override fun get(): Flow<Fingerprint?> = dataPref
}
