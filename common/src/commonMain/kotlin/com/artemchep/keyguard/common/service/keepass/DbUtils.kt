package com.artemchep.keyguard.common.service.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encodeTo
import app.keemobile.kotpass.models.Meta
import com.artemchep.keyguard.common.exception.KeePassFileAlreadyExistsException
import com.artemchep.keyguard.util.foundation.io.readByteArrayAndClose
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseMetadata
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseStorage
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseStorageLocalFile
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseStorageWebDav
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseWriteMode
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.webdav.WebDavClientFactory
import com.artemchep.keyguard.common.service.webdav.parseWebDavKeePassFileUrl
import com.artemchep.keyguard.common.service.webdav.toWebDavAuthorization
import com.artemchep.keyguard.common.service.webdav.webDavAuthorizationOf
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.core.store.bitwarden.FileLocation
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.random.Random

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
class StagedDatabase(
    val bytes: ByteArray,
)

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
): KeePassDatabaseMetadata? = withContext(Dispatchers.Default) {
    // Encode into memory first and immediately decode the result. This catches
    // corrupt, truncated, or credential-incompatible output before any existing
    // local/WebDAV database is touched.
    val staged = stageAndVerify(
        database = database,
        credentials = credentials,
    )

    withContext(Dispatchers.IO) {
        // Publish the verified bytes as the final step.
        storage.publish(
            mode = mode,
            staged = staged,
            expected = expectedMetadata,
        )
    }
}

/**
 * Encodes [database] and verifies the result is readable before it is
 * published. The re-decode guards against a truncated or otherwise corrupt
 * encode reaching the destination.
 */
private suspend fun stageAndVerify(
    database: KeePassDatabase,
    credentials: Credentials,
): StagedDatabase = withContext(Dispatchers.Default) {
    val sink = Buffer()
    database.encodeTo(
        sink = sink,
        cipherProviders = keePassCipherProviders,
    )
    val bytes = sink.readByteArray()
    // Verify the encoded payload round-trips before we trust it.
    Buffer()
        .apply { write(bytes) }
        .use { source ->
            KeePassDatabase.decode(
                source = source,
                credentials = credentials,
                cipherProviders = keePassCipherProviders,
            )
        }
    StagedDatabase(
        bytes = bytes,
    )
}

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
): KeePassDatabase = storage.read()
    .use { source ->
        KeePassDatabase.decode(
            source = source,
            credentials = credentials,
            cipherProviders = keePassCipherProviders,
        )
    }

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
