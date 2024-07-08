package db_key_value.crypto_prefs

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.SecureKeyValueStore
import com.artemchep.keyguard.platform.recordLog
import db_key_value.shared_prefs.SharedPrefsKeyValueStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

class SecurePrefKeyValueStore(
    context: Context,
    file: String,
) : SecureKeyValueStore,
    KeyValueStore by SharedPrefsKeyValueStore(
        createPrefs = {
            getEncryptedSharedPrefsOrRecreate(
                context = context,
                file = file,
            )
        },
    )

@SuppressLint("ApplySharedPref")
private fun getEncryptedSharedPrefsOrRecreate(
    context: Context,
    file: String,
) = run {
    lateinit var exception: Throwable

    val retryCount = 3
    for (i in 0 until retryCount) {
        try {
            return@run getEncryptedSharedPrefs(context, file)
        } catch (e: Exception) {
            if (
                e is IOException ||
                e is GeneralSecurityException ||
                // For some reason once in a while I get a device that
                // crashes here. Maybe for some the exception is not the
                // default one.
                "KeyStore" in e.message.orEmpty()
            ) {
                exception = e
                clearSharedPreferences(
                    context = context,
                    file = file,
                )
                clearKeystore(alias = file)
            } else {
                recordLog("Failed to read the shared preferences!")
                throw e
            }
        }
    }

    throw exception
}

@Synchronized
private fun getEncryptedSharedPrefs(
    context: Context,
    file: String,
) = run {
    val masterKeyAlias = getMasterKeyAlias(
        context = context,
        alias = file,
        onError = {
            clearSharedPreferences(
                context = context,
                file = file,
            )
            clearKeystore(alias = file)
        },
    )
    EncryptedSharedPreferences.create(
        context,
        file,
        masterKeyAlias,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

@Synchronized
private fun getMasterKeyAlias(
    context: Context,
    alias: String,
    onError: () -> Unit,
): MasterKey {
    lateinit var exception: Throwable

    var strongBox = true
    val retryCount = 3
    for (i in 0 until retryCount) {
        try {
            return MasterKey.Builder(context, alias)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(strongBox)
                .setUserAuthenticationRequired(false)
                .build()
        } catch (e: Exception) {
            if (e.isStrongBoxUnavailableExceptionCompat()) {
                // If the StrongBox Keymaster isn't available for the given algorithm
                // and key size associated with a key, the framework throws a
                // StrongBoxUnavailableException. If you get this exception,
                // try using TEE for your key storage as a fallback option.
                //
                // https://developer.android.com/privacy-and-security/keystore#HardwareSecurityModule
                strongBox = false
            } else if (e is GeneralSecurityException) {
                exception = e
                onError()
            } else {
                throw e
            }
        }
    }

    throw exception
}

@SuppressLint("ApplySharedPref")
private fun clearSharedPreferences(
    context: Context,
    file: String,
) {
    // Clear the shared preferences
    context.getSharedPreferences(file, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
}

private fun clearKeystore(
    alias: String,
) {
    // clear the master key
    // https://issuetracker.google.com/issues/176215143
    val keyStore = KeyStore.getInstance("AndroidKeyStore")
    keyStore.load(null)
    keyStore.deleteEntry(alias)
}

private fun Throwable.isStrongBoxUnavailableExceptionCompat(): Boolean =
    Build.VERSION.SDK_INT >= 28 && isStrongBoxUnavailableException()

@RequiresApi(Build.VERSION_CODES.P)
private fun Throwable.isStrongBoxUnavailableException(): Boolean =
    this is StrongBoxUnavailableException ||
            cause?.isStrongBoxUnavailableException() == true
