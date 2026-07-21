package db_key_value.datastore.encrypted

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.security.crypto.EncryptedFile
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.SecureKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogRepository
import db_key_value.datastore.DataStoreKeyValueStore
import db_key_value.datastore.getDataStoreFile
import io.github.osipxd.security.crypto.createEncrypted
import java.io.File
import java.io.IOException
import java.io.InputStream

internal val DATA_STORE_FILE_ENCRYPTION_SCHEME = EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB

internal class SecureDataStoreKeyValueStore(
    context: Context,
    file: String,
    logRepository: LogRepository,
    secureStorageCoordinator: SecureStorageCoordinator,
    backingStore: KeyValueStore? = null,
) : SecureKeyValueStore,
    KeyValueStore by DataStoreKeyValueStore(
        provideFile = {
            getDataStoreFile(
                context = context,
                file = file,
            )
        },
        createDataStore = { dataStoreFile ->
            getEncryptedDataStore(
                context = context,
                dataStoreFile = dataStoreFile,
                store = file,
                secureStorageCoordinator = secureStorageCoordinator,
            )
        },
        logTag = file,
        logRepository = logRepository,
        backingStore = backingStore,
    )

private suspend fun getEncryptedDataStore(
    context: Context,
    dataStoreFile: File,
    store: String,
    secureStorageCoordinator: SecureStorageCoordinator,
) = secureStorageCoordinator.openStore(
    store = store,
    probe = { masterKeyAlias ->
        probeDecryptable(context, dataStoreFile, masterKeyAlias)
    },
    open = { encryptedFile ->
        PreferenceDataStoreFactory.createEncrypted {
            encryptedFile
        }
    },
)

private fun encryptedFile(
    context: Context,
    dataStoreFile: File,
    masterKeyAlias: String,
): EncryptedFile = EncryptedFile
    .Builder(
        dataStoreFile,
        context,
        masterKeyAlias,
        DATA_STORE_FILE_ENCRYPTION_SCHEME,
    )
    .build()

/**
 * Forces a real decrypt of the existing ciphertext so unreadable data is detected up
 * front (and recovered by the coordinator) instead of silently degrading to defaults.
 * Runs before this store instance's DataStore is built, so the probe does not race
 * that instance's reads and writes.
 *
 * Tink intentionally collapses both authentication failures and underlying I/O
 * failures into IOException, so a failed decrypt only counts as corruption when the
 * raw file is provably readable and a second decrypt attempt still fails.
 */
private fun probeDecryptable(
    context: Context,
    dataStoreFile: File,
    masterKeyAlias: String,
): EncryptedFile {
    // Build once inside the coordinator's classified probe path, then pass this exact
    // primitive into DataStore creation. Rebuilding it afterwards would create an
    // unclassified keystore/keyset failure window.
    val encryptedFile = encryptedFile(context, dataStoreFile, masterKeyAlias)

    // A brand-new store has nothing to decrypt yet.
    if (!encryptedPayloadRequiresProbe(dataStoreFile)) {
        return encryptedFile
    }

    try {
        decryptEntireFile(encryptedFile)
        return encryptedFile
    } catch (_: IOException) {
        // Fall through to the raw-read check and the retry below.
    }

    ensureRawFileReadable(dataStoreFile)
    try {
        decryptEntireFile(encryptedFile)
    } catch (secondFailure: IOException) {
        ensureRawFileReadable(dataStoreFile)
        throw CorruptionException(
            message = "Encrypted DataStore payload cannot be decrypted",
            cause = secondFailure,
        )
    }
    return encryptedFile
}

internal fun encryptedPayloadRequiresProbe(dataStoreFile: File): Boolean {
    if (!dataStoreFile.exists()) {
        return false
    }
    if (dataStoreFile.length() == 0L) {
        // File.length() also returns zero when metadata cannot be read. Prove that the
        // payload itself is readable before declaring it corrupt and allowing a wipe.
        ensureRawFileReadable(dataStoreFile)
        throw CorruptionException("Encrypted DataStore payload is empty")
    }
    return true
}

/**
 * Throws the underlying IOException when the raw ciphertext cannot even be read, so
 * a transient storage error is reported as such (and retried later) instead of being
 * declared corruption and wiped.
 */
private fun ensureRawFileReadable(dataStoreFile: File) {
    dataStoreFile.inputStream().use(InputStream::drain)
}

private fun decryptEntireFile(encryptedFile: EncryptedFile) {
    encryptedFile
        .openFileInput()
        .use(InputStream::drain)
}

private fun InputStream.drain() {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (read(buffer) != -1) {
        // Reading to EOF authenticates every encrypted segment.
    }
}
