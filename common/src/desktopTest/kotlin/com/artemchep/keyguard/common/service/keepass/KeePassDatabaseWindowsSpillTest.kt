package com.artemchep.keyguard.common.service.keepass

import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.models.BinaryData
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KeePassDatabaseWindowsSpillTest {
    private val fileService = FileServiceImpl()
    private val base64Service = Base64ServiceJvm()

    @Test
    fun largeDatabaseSpillsPublishesAndReopens() = runTest {
        val directory = Files.createTempDirectory("keyguard-keepass-windows-spill-test-")
        try {
            val databasePath = directory.resolve("vault.kdbx")
            val databaseUri = databasePath.toUri().toString()
            prepareKeePassDatabase(
                fileService = fileService,
                params = AddKeePassAccountParams(
                    mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                    dbUri = databaseUri,
                    dbFileName = "vault.kdbx",
                    webDav = null,
                    keyUri = null,
                    password = PASSWORD,
                ),
            )

            val token = KeePassToken(
                id = "windows-spill-test",
                key = KeePassToken.Key(
                    passwordBase64 = base64Service.encodeToString(PASSWORD),
                    keyBase64 = null,
                ),
                files = KeePassToken.Files(
                    databaseUri = databaseUri,
                    databaseFileName = "vault.kdbx",
                ),
            )
            val database = openKeePassDatabase(
                token = token,
                fileService = fileService,
                base64Service = base64Service,
            )
            val attachmentBytes = ByteArray(LARGE_ATTACHMENT_BYTES).also {
                Random(0x4B444258).nextBytes(it)
            }
            val attachment = BinaryData.Uncompressed(
                memoryProtection = false,
                rawContent = attachmentBytes,
            )
            val updated = database.modifyBinaries { binaries ->
                binaries + (attachment.hash to attachment)
            }

            saveKeePassDatabase(
                fileService = fileService,
                token = token,
                database = updated,
                base64Service = base64Service,
            )

            assertTrue(Files.size(databasePath) > MAX_IN_MEMORY_STAGED_DATABASE_BYTES)
            val reopened = openKeePassDatabase(
                token = token,
                fileService = fileService,
                base64Service = base64Service,
            )
            val reopenedAttachment = reopened.binaries[attachment.hash]
            assertNotNull(reopenedAttachment)
            assertContentEquals(attachmentBytes, reopenedAttachment.getContent())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val PASSWORD = "secret"
        const val LARGE_ATTACHMENT_BYTES = 9 * 1024 * 1024
    }
}
