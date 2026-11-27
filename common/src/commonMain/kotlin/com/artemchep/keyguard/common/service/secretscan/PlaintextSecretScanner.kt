package com.artemchep.keyguard.common.service.secretscan

import kotlinx.coroutines.flow.Flow

/**
 * Finds plaintext SSH private keys and `.env` files on disk. Bound only where
 * the app can read the user's files directly (the JVM desktop app).
 */
interface PlaintextSecretScanner {
    /**
     * Whether [scan] can walk the home folder by itself. `false` where the
     * sandbox hides it (Flatpak); only the extra roots are scanned then.
     */
    val defaultRootsAvailable: Boolean

    /**
     * Whether [remove] moves the files to the system Trash. When it is
     * `false`, [remove] deletes them permanently.
     */
    val trashSupported: Boolean

    /**
     * Scans the default roots, when [defaultRootsAvailable], plus [extraRoots]
     * for the files of the [kind]. The flow ends with [SecretScanEvent.Done];
     * cancel it to stop the walk.
     */
    fun scan(
        kind: SecretScanKind,
        extraRoots: List<String>,
    ): Flow<SecretScanEvent>

    /**
     * The current content of the file of [item], or `null` if it is no longer
     * a regular file within the size limit or its SHA-256 changed since the
     * scan. The caller should clear the bytes after use, see [readWiped].
     */
    suspend fun read(item: SecretScanItem): ByteArray?

    /**
     * Moves the files of the [items] to the system Trash if [trashSupported],
     * or deletes them permanently otherwise. A file whose SHA-256 changed
     * since the scan is kept. Returns the result per path.
     */
    suspend fun remove(items: List<SecretScanItem>): Map<String, Boolean>
}

/**
 * Runs [block] on the content of the file of [item] and clears the content
 * afterwards. `null` if the file changed since the scan, see
 * [PlaintextSecretScanner.read].
 */
suspend inline fun <T : Any> PlaintextSecretScanner.readWiped(
    item: SecretScanItem,
    block: (ByteArray) -> T,
): T? = read(item)?.useWiped(block)

/** Runs [block] on the array and clears the array afterwards. */
inline fun <T> ByteArray.useWiped(
    block: (ByteArray) -> T,
): T = try {
    block(this)
} finally {
    fill(0)
}

/** What a scan looks for. */
enum class SecretScanKind {
    /** Unencrypted or encrypted SSH private keys, see [SecretScanItem.SshKey]. */
    SshKey,

    /** `.env` files with literal values, see [SecretScanItem.DotEnv]. */
    DotEnv,
}

sealed interface SecretScanEvent {
    /** The number of regular files checked so far. */
    data class Progress(
        val checked: Int,
    ) : SecretScanEvent

    data class Found(
        val item: SecretScanItem,
    ) : SecretScanEvent

    data class Done(
        /** The roots that were scanned, for display: `~` for the home folder. */
        val scannedRoots: List<String>,
    ) : SecretScanEvent
}

/** A finding. Holds no secret values. */
sealed interface SecretScanItem {
    /** The absolute path of the file. */
    val path: String

    /** The [path] for display: `~` for the home folder. */
    val displayPath: String

    /** The lowercase hex SHA-256 of the file content at scan time. */
    val sha256: String

    data class SshKey(
        override val path: String,
        override val displayPath: String,
        override val sha256: String,
        /** `true` if the key needs a passphrase to be read. */
        val encrypted: Boolean,
        /** The public key fingerprint, or `null` if the key is [encrypted]. */
        val fingerprint: String?,
    ) : SecretScanItem

    data class DotEnv(
        override val path: String,
        override val displayPath: String,
        override val sha256: String,
        /** The number of variables with a non-empty literal value. */
        val variableCount: Int,
        /** The [secretScanLiteralDigest] of the file at scan time. */
        val literalDigest: String,
    ) : SecretScanItem
}
