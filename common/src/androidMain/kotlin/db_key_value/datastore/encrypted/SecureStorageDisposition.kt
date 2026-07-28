package db_key_value.datastore.encrypted

import com.artemchep.keyguard.common.service.logging.LogLevel

/**
 * How the coordinator should react to a failure while provisioning or reading
 * secure storage.
 */
internal sealed interface SecureStorageDisposition {
    val code: SecureStorageFailureCode

    /** Flaky failure */
    data class Retry(
        override val code: SecureStorageFailureCode,
        val delayMillis: Long,
        val maxAttempts: Int,
        /**
         * When the retries are exhausted, escalate to a key-material wipe instead of
         * just giving up. Key-material initialization performs the wipe and retries
         * once; the payload-read path cannot wipe shared keys itself, so it only
         * invalidates the cached key material and the next access re-runs the full
         * initialization, where the wipe happens if the failure reproduces. Used for
         * ambiguous crypto errors that a clean slate may fix, and never for plain
         * I/O errors that must not destroy data.
         */
        val wipeOnExhaustion: Boolean,
    ) : SecureStorageDisposition

    /** Definitive corruption: wipe the affected artifacts and recreate them. */
    data class Wipe(
        override val code: SecureStorageFailureCode,
    ) : SecureStorageDisposition

    /**
     * Unrecoverable in this process: degrade to the default value and retry on the
     * next launch (the keystore hardware is unavailable, out of memory, etc.).
     */
    data class Degrade(
        override val code: SecureStorageFailureCode,
    ) : SecureStorageDisposition
}

internal fun SecureStorageDisposition.logLevel(
): LogLevel = when (this) {
    is SecureStorageDisposition.Retry -> LogLevel.INFO
    is SecureStorageDisposition.Wipe -> LogLevel.WARNING
    is SecureStorageDisposition.Degrade -> LogLevel.ERROR
}

internal fun SecureStorageDisposition.logMessage(
): String = when (this) {
    is SecureStorageDisposition.Retry -> {
        "disposition=retry code=$code delay=$delayMillis max_attempts=$maxAttempts"
    }

    is SecureStorageDisposition.Wipe -> {
        "disposition=wipe code=$code"
    }

    is SecureStorageDisposition.Degrade -> {
        "disposition=degrade code=$code"
    }
}
