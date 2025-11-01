package com.artemchep.keyguard.common.service.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val credentials = kotlin.run {
        val passphrase = EncryptedValue.fromBase64(base64 = token.key.passwordBase64)
        if (keyData != null) {
            Credentials.from(passphrase, keyData)
        } else Credentials.from(passphrase)
    }
    // Try to decode a database and check that it is actually real
    // and readable with the given credentials.
    val database = fileService.readFromFile(token.files.databaseUri)
        .use { inputStream ->
            KeePassDatabase.decode(inputStream, credentials)
        }
    database
}

suspend fun saveKeePassDatabase(
    fileService: FileService,
    token: KeePassToken,
    database: KeePassDatabase,
): KeePassDatabase = withContext(Dispatchers.IO) {
    fileService.writeToFile(token.files.databaseUri)
        .use { outputStream ->
            database.encode(outputStream)
        }
}
