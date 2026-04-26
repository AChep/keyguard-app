package com.artemchep.keyguard.common.service.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.models.Meta
import com.artemchep.keyguard.common.exception.KeePassFileAlreadyExistsException
import com.artemchep.keyguard.common.io.readByteArrayAndClose
import com.artemchep.keyguard.common.io.toInputStream
import com.artemchep.keyguard.common.io.toOutputStream
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class PreparedKeePassDatabase(
    val keyData: ByteArray?,
)

suspend fun openKeePassDatabase(
    token: KeePassToken,
    fileService: FileService,
    base64Service: Base64Service,
): KeePassDatabase = withContext(Dispatchers.IO) {
    val keyData = token.key.keyBase64
        ?.let(base64Service::decode)
    openKeePassDatabase(
        fileService = fileService,
        token = token,
        keyData = keyData,
    )
}

suspend fun openKeePassDatabase(
    fileService: FileService,
    token: KeePassToken,
    keyData: ByteArray?,
): KeePassDatabase = withContext(Dispatchers.IO) {
    val credentials = createKeePassCredentials(
        passphrase = EncryptedValue.fromBase64(base64 = token.key.passwordBase64),
        keyData = keyData,
    )
    openKeePassDatabase(
        fileService = fileService,
        dbUri = token.files.databaseUri,
        credentials = credentials,
    )
}

suspend fun prepareKeePassDatabase(
    fileService: FileService,
    params: AddKeePassAccountParams,
): PreparedKeePassDatabase = withContext(Dispatchers.IO) {
    val keyData = loadKeePassKeyData(
        fileService = fileService,
        keyUri = params.keyUri,
    )
    val credentials = createKeePassCredentials(
        passphrase = EncryptedValue.fromString(text = params.password),
        keyData = keyData,
    )

    when (val mode = params.mode) {
        is AddKeePassAccountParams.Mode.New -> {
            if (!mode.allowOverwrite && fileService.exists(params.dbUri)) {
                throw KeePassFileAlreadyExistsException()
            }

            createKeePassDatabase(
                fileService = fileService,
                dbUri = params.dbUri,
                credentials = credentials,
            )
        }

        AddKeePassAccountParams.Mode.Open -> {
            // Do nothing.
        }
    }

    openKeePassDatabase(
        fileService = fileService,
        dbUri = params.dbUri,
        credentials = credentials,
    )

    PreparedKeePassDatabase(
        keyData = keyData,
    )
}

suspend fun saveKeePassDatabase(
    fileService: FileService,
    token: KeePassToken,
    database: KeePassDatabase,
): KeePassDatabase = withContext(Dispatchers.IO) {
    fileService.writeToFile(token.files.databaseUri)
        .toOutputStream()
        .use { outputStream ->
            database.encode(outputStream)
        }
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

private fun openKeePassDatabase(
    fileService: FileService,
    dbUri: String,
    credentials: Credentials,
): KeePassDatabase = fileService.readFromFile(dbUri)
    .toInputStream()
    .use { inputStream ->
        KeePassDatabase.decode(inputStream, credentials)
    }

private fun createKeePassDatabase(
    fileService: FileService,
    dbUri: String,
    credentials: Credentials,
) {
    val database = KeePassDatabase.Ver4x.create(
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

    fileService.writeToFile(dbUri)
        .toOutputStream()
        .use(database::encode)
}

private fun loadKeePassKeyData(
    fileService: FileService,
    keyUri: String?,
): ByteArray? {
    keyUri ?: return null
    return fileService.readFromFile(keyUri).readByteArrayAndClose()
}
