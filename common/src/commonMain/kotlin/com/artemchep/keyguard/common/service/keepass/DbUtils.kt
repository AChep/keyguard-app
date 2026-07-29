package com.artemchep.keyguard.common.service.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encodeTo
import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.Meta
import com.artemchep.keyguard.common.exception.KeePassFileAlreadyExistsException
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseMetadata
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseStorage
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseStorageLocalFile
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseStorageWebDav
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseWriteMode
import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.webdav.WebDavClientFactory
import com.artemchep.keyguard.common.service.webdav.parseWebDavKeePassFileUrl
import com.artemchep.keyguard.common.service.webdav.toWebDavAuthorization
import com.artemchep.keyguard.common.service.webdav.webDavAuthorizationOf
import com.artemchep.keyguard.common.util.RetryPolicy
import com.artemchep.keyguard.common.util.retryWithPolicy
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.core.store.bitwarden.FileLocation
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import com.artemchep.keyguard.ui.icons.generateAccentColors
import com.artemchep.keyguard.util.io.readByteArrayAndClose
import com.artemchep.keyguard.util.io.spool.ByteSnapshot
import com.artemchep.keyguard.util.io.spool.buildVerifiedSnapshot
import com.artemchep.keyguard.util.io.spool.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

data class PreparedKeePassDatabase(
    val keyData: ByteArray?,
)

suspend fun openKeePassDatabase(
    token: KeePassToken,
    fileService: FileService,
    base64Service: Base64Service,
    webDavClientFactory: WebDavClientFactory? = null,
): KeePassDatabase = withContext(Dispatchers.IO) {
    val keyData = token.key.keyBase64
        ?.let(base64Service::decode)
    openKeePassDatabase(
        fileService = fileService,
        token = token,
        keyData = keyData,
        webDavClientFactory = webDavClientFactory,
    )
}

suspend fun openKeePassDatabase(
    fileService: FileService,
    token: KeePassToken,
    keyData: ByteArray?,
    webDavClientFactory: WebDavClientFactory? = null,
): KeePassDatabase = withContext(Dispatchers.IO) {
    val credentials = createKeePassCredentials(
        passphrase = EncryptedValue.fromBase64(base64 = token.key.passwordBase64),
        keyData = keyData,
    )
    openKeePassDatabase(
        storage = createKeePassDatabaseStorage(
            fileService = fileService,
            token = token,
            webDavClientFactory = webDavClientFactory,
        ),
        credentials = credentials,
    )
}

suspend fun prepareKeePassDatabase(
    fileService: FileService,
    params: AddKeePassAccountParams,
    webDavClientFactory: WebDavClientFactory? = null,
): PreparedKeePassDatabase = withContext(Dispatchers.IO) {
    val keyData = loadKeePassKeyData(
        fileService = fileService,
        keyUri = params.keyUri,
        keyAccessToken = params.keyAccessToken,
    )
    val credentials = createKeePassCredentials(
        passphrase = EncryptedValue.fromString(text = params.password),
        keyData = keyData,
    )
    val storage = createKeePassDatabaseStorage(
        fileService = fileService,
        params = params,
        webDavClientFactory = webDavClientFactory,
    )

    when (val mode = params.mode) {
        is AddKeePassAccountParams.Mode.New -> {
            if (!mode.allowOverwrite && storage.exists()) {
                throw KeePassFileAlreadyExistsException()
            }

            val database = createKeePassDatabase(credentials)
            with(storage) {
                // CreateOrReplace is used because this function saves changes
                // to an existing account database.
                stageVerifyAndPublish(
                    database = database,
                    credentials = credentials,
                    mode = if (mode.allowOverwrite) {
                        KeePassDatabaseWriteMode.CreateOrReplace
                    } else {
                        KeePassDatabaseWriteMode.Create
                    },
                )
            }
        }

        AddKeePassAccountParams.Mode.Open -> {
            // Do nothing.
        }
    }

    openKeePassDatabase(
        storage = storage,
        credentials = credentials,
    )

    PreparedKeePassDatabase(
        keyData = keyData,
    )
}

/**
 * An encoded KeePass database that has been verified to round-trip
 * (re-decoded successfully with the same credentials) and is ready to be
 * published to its destination.
 */
internal class StagedDatabase(
    private val snapshot: ByteSnapshot,
) : AutoCloseable {
    val size: Long
        get() = snapshot.size

    fun source() = snapshot.openSource()

    fun replayTo(output: Sink) = snapshot.copyTo(output)

    override fun close() = snapshot.close()
}

/**
 * Saves [database] durably using a stage -> verify -> publish sequence.
 *
 * @return the destination metadata after publishing, or
 *   `null` when it could not be read back.
 */
suspend fun saveKeePassDatabase(
    fileService: FileService,
    token: KeePassToken,
    database: KeePassDatabase,
    base64Service: Base64Service,
    webDavClientFactory: WebDavClientFactory? = null,
    expectedMetadata: KeePassDatabaseMetadata? = null,
): KeePassDatabaseMetadata? = withContext(Dispatchers.Default) {
    // Create KDBX credentials
    val credentials = kotlin.run {
        val keyData = token.key.keyBase64
            ?.let(base64Service::decode)
        createKeePassCredentials(
            passphrase = EncryptedValue.fromBase64(base64 = token.key.passwordBase64),
            keyData = keyData,
        )
    }
    // Create storage
    val storage = createKeePassDatabaseStorage(
        fileService = fileService,
        token = token,
        webDavClientFactory = webDavClientFactory,
    )

    with(storage) {
        // CreateOrReplace is used because this function saves changes
        // to an existing account database.
        stageVerifyAndPublish(
            database = database,
            credentials = credentials,
            mode = KeePassDatabaseWriteMode.CreateOrReplace,
            expectedMetadata = expectedMetadata,
        )
    }
}

context(
    storage: KeePassDatabaseStorage,
)
internal suspend fun stageVerifyAndPublish(
    database: KeePassDatabase,
    credentials: Credentials,
    mode: KeePassDatabaseWriteMode,
    expectedMetadata: KeePassDatabaseMetadata? = null,
    stagingSpoolFactory: StagingSpoolFactory = DefaultStagingSpoolFactory(),
): KeePassDatabaseMetadata? = withContext(Dispatchers.IO) {
    // Encode into private replayable storage and immediately decode the result.
    // This catches corrupt, truncated, or credential-incompatible output before
    // any existing local/WebDAV database is touched. Staging may perform blocking
    // file I/O after crossing the adaptive memory threshold, so the whole
    // stage -> verify -> publish sequence runs on the IO dispatcher. The stage
    // call deliberately has no withContext of its own: a context-switch boundary
    // would discard the staged storage if the caller got cancelled mid-stage,
    // leaking it with no one left to close it.
    val staged = stageAndVerify(
        database = database,
        credentials = credentials,
        stagingSpoolFactory = stagingSpoolFactory,
    )
    try {
        // The blocking stage cannot observe a cancellation; do not start
        // the publish if the caller is already gone.
        ensureActive()
        // Publish the verified stage as the final step.
        storage.publish(
            mode = mode,
            staged = staged,
            expected = expectedMetadata,
        )
    } finally {
        // Release the memory or delete-on-close spill stage. A close failure
        // must not replace the publish outcome.
        runCatching { staged.close() }
    }
}

/**
 * Encodes [database] and verifies the result is readable before it is
 * published. The re-decode guards against a truncated or otherwise corrupt
 * encode reaching the destination.
 */
private fun stageAndVerify(
    database: KeePassDatabase,
    credentials: Credentials,
    stagingSpoolFactory: StagingSpoolFactory,
): StagedDatabase {
    val snapshot = stagingSpoolFactory.create(
        purpose = StagingPurpose.KeePassDatabase,
        limits = SpoolLimits(
            memoryBytes = MAX_IN_MEMORY_STAGED_DATABASE_BYTES,
            maximumBytes = MAX_STAGED_DATABASE_BYTES,
        ),
        limitExceeded = { limit ->
            IOException(
                "Encoded KeePass database exceeds the supported staging limit of $limit bytes",
            )
        },
    ).buildVerifiedSnapshot(
        write = { sink ->
            database.encodeTo(
                sink = sink,
                cipherProviders = keePassCipherProviders,
            )
        },
        verify = { source ->
            // Verify the encoded payload round-trips before we trust it.
            KeePassDatabase.decode(
                source = source,
                credentials = credentials,
                cipherProviders = keePassCipherProviders,
            )
        },
    )
    return StagedDatabase(snapshot)
}

internal const val MAX_IN_MEMORY_STAGED_DATABASE_BYTES = 8L * 1024L * 1024L
private const val MAX_STAGED_DATABASE_BYTES = 2_147_483_647L

suspend fun getKeePassDatabaseMetadata(
    fileService: FileService,
    token: KeePassToken,
    webDavClientFactory: WebDavClientFactory? = null,
): KeePassDatabaseMetadata? = withContext(Dispatchers.IO) {
    val storage = createKeePassDatabaseStorage(
        fileService = fileService,
        token = token,
        webDavClientFactory = webDavClientFactory,
    )
    storage.stat()
}

private fun createKeePassCredentials(
    passphrase: EncryptedValue,
    keyData: ByteArray?,
): Credentials =
    if (keyData != null) {
        Credentials.from(passphrase, keyData)
    } else {
        Credentials.from(passphrase)
    }

private suspend fun openKeePassDatabase(
    storage: KeePassDatabaseStorage,
    credentials: Credentials,
): KeePassDatabase = storage.readWithDecodeRetry { source ->
    KeePassDatabase.decode(
        source = source,
        credentials = credentials,
        cipherProviders = keePassCipherProviders,
    )
}

internal suspend fun <T> KeePassDatabaseStorage.readWithDecodeRetry(
    decode: (Source) -> T,
): T = retryWithPolicy(
    policy = RetryPolicy(
        maxAttempts = decodeReadAttempts.coerceAtLeast(1),
        delayBeforeRetry = { KEEPASS_REMOTE_READ_RETRY_DELAY },
        shouldRetry = { e ->
            e is Exception &&
                (isRetryableReadFailure(e) || e.isRetryableStaleDecodeFailure())
        },
    ),
) { read().use(decode) }

/**
 * A decode failure that can be caused by a remote backend briefly exposing
 * a stale or torn representation of the database, rather than by the file
 * itself being unreadable.
 */
private fun Exception.isRetryableStaleDecodeFailure(): Boolean = when (this) {
    is FormatError.InvalidXml,
    is FormatError.InvalidContent,
    is FormatError.InvalidHeader,
    is FormatError.FailedCompression,
    is CryptoError.InvalidDataLength,
    is CryptoError.InvalidCipherText,
    -> true
    else -> false
}

private val KEEPASS_REMOTE_READ_RETRY_DELAY = 750.milliseconds

private fun createKeePassDatabase(
    credentials: Credentials,
) = KeePassDatabase.Ver4x.create(
    rootName = "Keyguard database",
    meta = Meta(
        name = "Keyguard database",
        color = kotlin.run {
            val hue = Random.nextDouble(0.0, 360.0)
                .toFloat()
            val color = generateAccentColors(hue).dark
            color.toHex()
        },
    ),
    credentials = credentials,
)

private val keePassCipherProviders = BaseCiphers.entries + TwofishCipher

private fun loadKeePassKeyData(
    fileService: FileService,
    keyUri: String?,
    keyAccessToken: String?,
): ByteArray? {
    keyUri ?: return null
    return fileService
        .readFromFile(
            uri = keyUri,
            accessToken = keyAccessToken?.let(::FileAccessToken),
        )
        .readByteArrayAndClose()
}

private fun createKeePassDatabaseStorage(
    fileService: FileService,
    params: AddKeePassAccountParams,
    webDavClientFactory: WebDavClientFactory?,
): KeePassDatabaseStorage =
    if (params.webDav != null) {
        KeePassDatabaseStorageWebDav(
            location = parseWebDavKeePassFileUrl(params.webDav.url),
            authorization = params.webDav.toWebDavAuthorization(),
            webDavClientFactory = requireNotNull(webDavClientFactory) {
                "WebDAV client factory is required for KeePass WebDAV databases."
            },
        )
    } else {
        KeePassDatabaseStorageLocalFile(
            fileService = fileService,
            uri = params.dbUri,
            accessToken = params.dbAccessToken?.let(::FileAccessToken),
        )
    }

private fun createKeePassDatabaseStorage(
    fileService: FileService,
    token: KeePassToken,
    webDavClientFactory: WebDavClientFactory?,
): KeePassDatabaseStorage =
    when (val location = token.database.location) {
        is FileLocation.Local -> KeePassDatabaseStorageLocalFile(
            fileService = fileService,
            uri = location.uri,
            accessToken = location.accessToken?.let(::FileAccessToken),
        )

        is FileLocation.WebDav -> KeePassDatabaseStorageWebDav(
            location = parseWebDavKeePassFileUrl(location.url),
            authorization = webDavAuthorizationOf(
                username = location.username,
                password = location.password,
            ),
            webDavClientFactory = requireNotNull(webDavClientFactory) {
                "WebDAV client factory is required for KeePass WebDAV databases."
            },
        )

        is FileLocation.Dropbox,
        is FileLocation.GoogleDrive,
        is FileLocation.OneDrive,
        is FileLocation.Sftp,
            -> throw UnsupportedOperationException(
            "KeePass database location is not supported yet: $location",
        )
    }
