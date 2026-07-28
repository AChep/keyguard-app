package db_key_value.datastore.encrypted

import android.os.Build
import android.security.KeyStoreException
import android.security.keystore.BackendBusyException
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.annotation.RequiresApi
import androidx.datastore.core.CorruptionException
import com.artemchep.keyguard.common.util.causeChain
import db_key_value.encrypted.isMalformedTinkKeyset
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import kotlin.math.min
import kotlin.random.Random

internal fun classifySecureStorageFailure(
    throwable: Throwable,
    stage: SecureStorageStage,
    attempt: Int,
    sdkInt: Int = Build.VERSION.SDK_INT,
    random: Random = Random.Default,
): SecureStorageDisposition {
    val causes = throwable.causeChain()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }

    // Definitive corruption of the ciphertext or the key that protects it.
    if (
        causes.any { cause ->
            cause is KeyPermanentlyInvalidatedException ||
                cause is UnrecoverableKeyException
        }
    ) {
        return SecureStorageDisposition.Wipe(SecureStorageFailureCode.KEY_UNAVAILABLE)
    }
    if (
        causes.any { cause ->
            cause is AEADBadTagException ||
                cause is BadPaddingException ||
                cause is CorruptionException ||
                // Tink reports malformed keysets as IOException subtypes. Both invalid
                // hex and invalid protobuf are definitive corruption, not transient I/O.
                (
                    stage == SecureStorageStage.KEYSET &&
                        cause.isMalformedTinkKeyset()
                )
        }
    ) {
        return SecureStorageDisposition.Wipe(SecureStorageFailureCode.INTEGRITY_FAILURE)
    }

    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        val keyStoreException = causes.filterIsInstance<KeyStoreException>().firstOrNull()
        if (keyStoreException != null) {
            classifyKeyStoreFailure(
                details = keyStoreException.toFailureDetails(),
                stage = stage,
                attempt = attempt,
                random = random,
            )?.let { return it }
        }
    }

    // The master key is created without a user-authentication requirement, so this
    // is unexpected; there is nothing to retry, degrade until the next launch.
    if (causes.any { cause -> cause is UserNotAuthenticatedException }) {
        return SecureStorageDisposition.Degrade(SecureStorageFailureCode.KEY_UNAVAILABLE)
    }

    if (sdkInt >= Build.VERSION_CODES.S) {
        val backendBusyException = causes.firstBackendBusyException()
        if (backendBusyException != null) {
            return SecureStorageDisposition.Retry(
                code = SecureStorageFailureCode.KEYSTORE_BUSY,
                delayMillis = backendBusyException.backOffHintMillis.coerceIn(0L, MAX_BACKOFF_MILLIS),
                maxAttempts = DEFAULT_MAX_ATTEMPTS,
                wipeOnExhaustion = false,
            )
        }
    }

    if (causes.any { cause -> cause is ProviderException } && sdkInt < Build.VERSION_CODES.TIRAMISU) {
        return SecureStorageDisposition.Retry(
            code = SecureStorageFailureCode.KEYSTORE_BUSY,
            delayMillis = random.nextLong(COMPAT_RETRY_MIN_MILLIS, COMPAT_RETRY_MAX_MILLIS + 1L),
            maxAttempts = COMPAT_MAX_ATTEMPTS,
            wipeOnExhaustion = false,
        )
    }

    // Plain I/O errors are transient and must never destroy data, so retry only.
    if (causes.any { cause -> cause is IOException }) {
        return SecureStorageDisposition.Retry(
            code = SecureStorageFailureCode.STORAGE_UNAVAILABLE,
            delayMillis = random.nextLong(COMPAT_RETRY_MIN_MILLIS, COMPAT_RETRY_MAX_MILLIS + 1L),
            maxAttempts = DEFAULT_MAX_ATTEMPTS,
            wipeOnExhaustion = false,
        )
    }

    // An ambiguous crypto error at any stage (including master-key creation): retry a
    // few times, then wipe as a last resort. The old code retried GeneralSecurityException
    // before giving up; wiping afterwards keeps a broken keystore from bricking the app.
    if (causes.any { cause -> cause is GeneralSecurityException }) {
        return SecureStorageDisposition.Retry(
            code = SecureStorageFailureCode.KEY_UNAVAILABLE,
            delayMillis = random.nextLong(COMPAT_RETRY_MIN_MILLIS, COMPAT_RETRY_MAX_MILLIS + 1L),
            maxAttempts = DEFAULT_MAX_ATTEMPTS,
            wipeOnExhaustion = true,
        )
    }

    return SecureStorageDisposition.Degrade(SecureStorageFailureCode.UNKNOWN_FAILURE)
}

internal data class KeyStoreFailureDetails(
    val numericErrorCode: Int,
    val requiresUserAuthentication: Boolean,
    val transientFailure: Boolean,
    val retryPolicy: Int,
)

internal fun classifyKeyStoreFailure(
    details: KeyStoreFailureDetails,
    stage: SecureStorageStage,
    attempt: Int,
    random: Random,
): SecureStorageDisposition? {
    if (
        details.numericErrorCode == KeyStoreException.ERROR_KEY_CORRUPTED ||
        details.numericErrorCode == KeyStoreException.ERROR_KEY_DOES_NOT_EXIST
    ) {
        return SecureStorageDisposition.Wipe(SecureStorageFailureCode.KEY_UNAVAILABLE)
    }
    if (details.requiresUserAuthentication) {
        return SecureStorageDisposition.Degrade(SecureStorageFailureCode.KEY_UNAVAILABLE)
    }
    if (details.numericErrorCode == KeyStoreException.ERROR_KEY_OPERATION_EXPIRED) {
        return SecureStorageDisposition.Retry(
            code = SecureStorageFailureCode.KEYSTORE_BUSY,
            delayMillis = 0L,
            maxAttempts = DEFAULT_MAX_ATTEMPTS,
            wipeOnExhaustion = false,
        )
    }
    if (!details.transientFailure) {
        return null
    }

    val delayMillis =
        when (details.retryPolicy) {
            KeyStoreException.RETRY_WITH_EXPONENTIAL_BACKOFF -> {
                val multiplier = 1L shl (attempt - 1).coerceIn(0, 3)
                val initialDelay =
                    random.nextLong(
                        KEYSTORE_RETRY_MIN_MILLIS,
                        KEYSTORE_RETRY_MAX_MILLIS + 1L,
                    )
                min(initialDelay * multiplier, MAX_BACKOFF_MILLIS)
            }

            // RETRY_WHEN_CONNECTIVITY_AVAILABLE / RETRY_AFTER_NEXT_REBOOT and anything else
            // collapse to a plain bounded retry: there is no online/reboot listener wired up.
            else -> {
                random.nextLong(KEYSTORE_RETRY_MIN_MILLIS, KEYSTORE_RETRY_MAX_MILLIS + 1L)
            }
        }
    return SecureStorageDisposition.Retry(
        code = SecureStorageFailureCode.KEYSTORE_BUSY,
        delayMillis = delayMillis,
        maxAttempts = DEFAULT_MAX_ATTEMPTS,
        wipeOnExhaustion = false,
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun KeyStoreException.toFailureDetails(): KeyStoreFailureDetails =
    KeyStoreFailureDetails(
        numericErrorCode = numericErrorCode,
        requiresUserAuthentication = requiresUserAuthentication(),
        transientFailure = isTransientFailure,
        retryPolicy = retryPolicy,
    )

@RequiresApi(Build.VERSION_CODES.S)
private fun List<Throwable>.firstBackendBusyException(): BackendBusyException? = filterIsInstance<BackendBusyException>().firstOrNull()

private const val DEFAULT_MAX_ATTEMPTS = 3
private const val COMPAT_MAX_ATTEMPTS = 2
private const val COMPAT_RETRY_MIN_MILLIS = 100L
private const val COMPAT_RETRY_MAX_MILLIS = 400L
private const val KEYSTORE_RETRY_MIN_MILLIS = 5_000L
private const val KEYSTORE_RETRY_MAX_MILLIS = 30_000L
private const val MAX_BACKOFF_MILLIS = 60_000L
