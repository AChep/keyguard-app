package db_key_value.datastore.encrypted

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.copy.SharedPreferencesStoreFactoryV2
import java.io.File
import java.io.IOException
import java.security.KeyStore

internal interface SecureStorageArtifacts {
    suspend fun inspect(): SecureStorageArtifactInventory

    suspend fun getOrCreateMasterKey(): String

    /** Forces the shared keyset to exist and be decryptable with the master key. */
    suspend fun validateKeyset(masterKeyAlias: String)

    /**
     * Removes every secure-storage artifact: the ciphertext files, the shared keyset
     * and the AndroidKeyStore master key. Used to recover from undecryptable state so
     * the app starts from a clean, working installation.
     */
    suspend fun wipeAll()

    /** Deletes the ciphertext of a single store, leaving the shared key material intact. */
    suspend fun wipeStore(store: String)
}

/**
 * Models the AndroidX Security Crypto artifact graph used by our encrypted DataStores.
 *
 * AndroidX [androidx.security.crypto.EncryptedFile] does not create independent key
 * material for every payload. Its default integration keeps one hex-encoded Tink
 * `StreamingAead` keyset in the private SharedPreferences file
 * `__androidx_security_crypto_encrypted_file_pref__.xml`, under the preference key
 * `__androidx_security_crypto_encrypted_file_keyset__`. That serialized keyset is
 * encrypted by the AndroidKeyStore master key returned by
 * `MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)`.
 *
 * `EncryptedFile.Builder.build()` is the operation that integrates these layers. On
 * first use it generates the shared keyset from [DATA_STORE_FILE_ENCRYPTION_SCHEME]
 * and commits it to the AndroidX preference file. On later uses it loads, decrypts and
 * parses the existing keyset, then creates the streaming-encryption primitive. The
 * encrypted DataStore payloads remain separate files under `datastore/`, but all of
 * them use that shared keyset and master key. A payload's file name is supplied as
 * associated data, so encrypted store files cannot be renamed or exchanged.
 *
 * Our integration treats the preference file, its keyset entry and the master key as
 * shared lifecycle artifacts:
 *
 * - [inspect] inventories payloads and both shared-key layers before a store opens. It
 *   loads SharedPreferences before checking the XML file so Android can restore a
 *   surviving `.xml.bak` after an interrupted commit.
 * - [getOrCreateMasterKey] provisions the AndroidKeyStore layer, while [validateKeyset]
 *   calls `EncryptedFile.Builder.build()` with a non-written probe path to provision or
 *   validate the AndroidX preference/keyset layer.
 * - Each payload is then decrypted once before its prepared primitive is handed to
 *   DataStore. [wipeStore] can discard one corrupt payload without rotating the shared
 *   keys.
 * - [wipeAll] removes every encrypted payload before deleting the shared keyset and
 *   master key. This ordering avoids leaving surviving ciphertext after its only
 *   decryption material has been destroyed.
 *
 * The SharedPreferences names above are AndroidX implementation details rather than a
 * public compatibility API. Changing the AndroidX Security dependency, encryption
 * scheme or builder configuration requires reviewing this inventory and recovery
 * contract together.
 */
internal class AndroidSecureStorageArtifacts(
    private val context: Context,
) : SecureStorageArtifacts {

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSET_PREFERENCES_NAME = "__androidx_security_crypto_encrypted_file_pref__"
        const val KEYSET_ALIAS = "__androidx_security_crypto_encrypted_file_keyset__"

        private val SECURE_STORE_NAMES: Set<String> =
            Files.entries
                .filterNot { file -> file in SharedPreferencesStoreFactoryV2.PLAINTEXT_FILES }
                .map { file -> file.filename }
                .toSet()
    }

    private val dataStoreDir by lazy {
        File(context.dataDir, "datastore")
    }

    private val keysetPreferencesFile by lazy {
        File(context.dataDir, "shared_prefs/$KEYSET_PREFERENCES_NAME.xml")
    }

    override suspend fun inspect(): SecureStorageArtifactInventory {
        val ciphertextStores = listSecureStoreFiles()
            .mapNotNullTo(mutableSetOf()) { file ->
                SECURE_STORE_NAMES
                    .firstOrNull { store -> file.belongsTo(store) }
            }

        val keysetPreferences =
            context.getSharedPreferences(KEYSET_PREFERENCES_NAME, Context.MODE_PRIVATE)
        val keysetPresent =
            isKeysetPresent(keysetPreferencesFile) {
                // Loading SharedPreferences first gives Android an opportunity to restore
                // a surviving `.xml.bak` file after an interrupted commit.
                keysetPreferences.contains(KEYSET_ALIAS)
            }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val masterKeyPresent = keyStore.containsAlias(MasterKeys.AES256_GCM_SPEC.keystoreAlias)
        return SecureStorageArtifactInventory(
            ciphertextStores = ciphertextStores,
            keysetPresent = keysetPresent,
            masterKeyPresent = masterKeyPresent,
        )
    }

    override suspend fun getOrCreateMasterKey(
    ): String = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    override suspend fun validateKeyset(masterKeyAlias: String) {
        // The probe file is never written to disk: existing stores must remain
        // readable when storage is full or temporarily read-only.
        val probeFile = File(context.noBackupFilesDir, "secure_storage/keyset_probe.v1")
        EncryptedFile
            .Builder(
                probeFile,
                context,
                masterKeyAlias,
                DATA_STORE_FILE_ENCRYPTION_SCHEME,
            )
            .build() // validation is a side-effect of the .build() method
    }

    override suspend fun wipeStore(store: String) {
        listSecureStoreFiles().forEach { file ->
            if (file.belongsTo(store)) {
                file.deleteOrThrow("secure store '$store'")
            }
        }
    }

    /**
     * See https://issuetracker.google.com/issues/176215143 for why the keystore entry
     * has to be deleted rather than reused.
     */
    override suspend fun wipeAll() {
        // Confirm every ciphertext file is gone before deleting the only key material
        // capable of decrypting it. A partial file-system failure must remain retryable.
        SECURE_STORE_NAMES.forEach { store -> wipeStore(store) }

        val keysetPreferences =
            context.getSharedPreferences(KEYSET_PREFERENCES_NAME, Context.MODE_PRIVATE)
        val keysetPreferencesCleared = keysetPreferences.edit().clear()
            .commit()
        if (!keysetPreferencesCleared || keysetPreferences.contains(KEYSET_ALIAS)) {
            throw IOException("Could not clear the secure storage keyset")
        }
        keysetPreferencesFile
            .deleteOrThrow("secure storage keyset")

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            .apply { load(null) }
        val masterKeyAlias = MasterKeys.AES256_GCM_SPEC.keystoreAlias
        keyStore.deleteEntry(masterKeyAlias)
        if (keyStore.containsAlias(masterKeyAlias)) {
            throw IOException("Could not delete the secure storage master key")
        }
    }

    private fun listSecureStoreFiles(
    ): Array<File> = when {
        !dataStoreDir.exists() -> {
            emptyArray()
        }

        dataStoreDir.isDirectory -> {
            dataStoreDir.listFiles()
                ?: throw IOException("Secure storage directory is unavailable")
        }

        else -> throw IOException("Secure storage path is not a directory")
    }

    private fun File.belongsTo(store: String): Boolean {
        val dataStoreFileName = "$store.preferences_pb"
        return name == dataStoreFileName || name.startsWith("$dataStoreFileName.")
    }
}

internal inline fun isKeysetPresent(
    keysetPreferencesFile: File,
    containsKeyset: () -> Boolean,
): Boolean = containsKeyset() && keysetPreferencesFile.exists()

private fun File.deleteOrThrow(description: String) {
    if (!exists()) {
        return
    }
    delete()
    if (exists()) {
        throw IOException("Could not delete $description artifact: $path")
    }
}
