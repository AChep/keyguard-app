package db_key_value.datastore.encrypted

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.SecureKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogRepository
import db_key_value.datastore.DataStoreKeyValueStore
import db_key_value.datastore.getDataStoreFile
import io.github.osipxd.security.crypto.createEncrypted
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore

class SecureDataStoreKeyValueStore(
    context: Context,
    file: String,
    logRepository: LogRepository,
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
            getEncryptedDataStoreOrRecreate(
                context = context,
                dataStoreFile = dataStoreFile,
            )
        },
        logTag = file,
        logRepository = logRepository,
        backingStore = backingStore,
    )

@SuppressLint("ApplySharedPref")
private fun getEncryptedDataStoreOrRecreate(
    context: Context,
    dataStoreFile: File,
) = run {
    PreferenceDataStoreFactory.createEncrypted {
        val keySpec = MasterKeys.AES256_GCM_SPEC
        val key = getMasterKey(
            keyGenParameterSpec = keySpec,
            onError = {
                dataStoreFile.delete()

                // clear the master key
                // https://issuetracker.google.com/issues/176215143
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                keyStore.deleteEntry(keySpec.keystoreAlias)
            },
        )
        EncryptedFile.Builder(
            dataStoreFile,
            context,
            key,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
    }
}

@Synchronized
private fun getMasterKey(
    keyGenParameterSpec: KeyGenParameterSpec,
    onError: () -> Unit,
): String {
    lateinit var exception: Throwable

    val retryCount = 3
    for (i in 0 until retryCount) {
        try {
            return MasterKeys.getOrCreate(keyGenParameterSpec)
        } catch (e: Exception) {
            if (e is GeneralSecurityException) {
                exception = e
                onError()
            } else {
                throw e
            }
        }
    }

    throw exception
}
