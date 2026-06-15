package com.artemchep.keyguard.common.service.keepass

import com.artemchep.keyguard.common.service.file.PureFileService
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeePassDatabasePreparationTest {
    private val fileService = PureFileService()
    private val base64Service = Base64ServiceJvm()

    @Test
    fun `existing db with correct password succeeds`() = runTest {
        withTempDir { dir ->
            val dbUri = dir.resolve("vault.kdbx").toUri().toString()
            prepareKeePassDatabase(
                fileService = fileService,
                params = params(
                    mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                    dbUri = dbUri,
                    password = "secret",
                ),
            )

            val prepared = prepareKeePassDatabase(
                fileService = fileService,
                params = params(
                    mode = AddKeePassAccountParams.Mode.Open,
                    dbUri = dbUri,
                    password = "secret",
                ),
            )

            assertNull(prepared.keyData)
            assertTrue(fileService.exists(dbUri))
        }
    }

    @Test
    fun `existing db with wrong password fails before import`() = runTest {
        withTempDir { dir ->
            val dbUri = dir.resolve("vault.kdbx").toUri().toString()
            prepareKeePassDatabase(
                fileService = fileService,
                params = params(
                    mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                    dbUri = dbUri,
                    password = "secret",
                ),
            )

            assertFails {
                prepareKeePassDatabase(
                    fileService = fileService,
                    params = params(
                        mode = AddKeePassAccountParams.Mode.Open,
                        dbUri = dbUri,
                        password = "wrong",
                    ),
                )
            }
        }
    }

    @Test
    fun `new db mode creates a readable database`() = runTest {
        withTempDir { dir ->
            val dbUri = dir.resolve("vault.kdbx").toUri().toString()
            prepareKeePassDatabase(
                fileService = fileService,
                params = params(
                    mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                    dbUri = dbUri,
                    password = "secret",
                ),
            )

            val database = openKeePassDatabase(
                token = KeePassToken(
                    id = "account-id",
                    key = KeePassToken.Key(
                        passwordBase64 = base64Service.encodeToString("secret"),
                        keyBase64 = null,
                    ),
                    files = KeePassToken.Files(
                        databaseUri = dbUri,
                        databaseFileName = "vault.kdbx",
                    ),
                ),
                fileService = fileService,
                base64Service = base64Service,
            )

            assertNotNull(database)
        }
    }

    @Test
    fun `new db mode refuses overwrite when target exists`() = runTest {
        withTempDir { dir ->
            val dbPath = dir.resolve("vault.kdbx")
            dbPath.writeBytes("existing".encodeToByteArray())

            assertFails {
                prepareKeePassDatabase(
                    fileService = fileService,
                    params = params(
                        mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                        dbUri = dbPath.toUri().toString(),
                        password = "secret",
                    ),
                )
            }
        }
    }

    @Test
    fun `key file flow works when key file is present`() = runTest {
        withTempDir { dir ->
            val dbUri = dir.resolve("vault.kdbx").toUri().toString()
            val keyUri = dir.resolve("vault.key").apply {
                writeBytes("key-file".encodeToByteArray())
            }.toUri().toString()

            val created = prepareKeePassDatabase(
                fileService = fileService,
                params = params(
                    mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                    dbUri = dbUri,
                    keyUri = keyUri,
                    password = "secret",
                ),
            )
            val reopened = prepareKeePassDatabase(
                fileService = fileService,
                params = params(
                    mode = AddKeePassAccountParams.Mode.Open,
                    dbUri = dbUri,
                    keyUri = keyUri,
                    password = "secret",
                ),
            )

            assertNotNull(created.keyData)
            assertNotNull(reopened.keyData)
        }
    }

    private fun params(
        mode: AddKeePassAccountParams.Mode,
        dbUri: String,
        password: String,
        keyUri: String? = null,
    ) = AddKeePassAccountParams(
        mode = mode,
        dbUri = dbUri,
        dbFileName = "vault.kdbx",
        keyUri = keyUri,
        password = password,
    )

    private inline fun withTempDir(
        block: (java.nio.file.Path) -> Unit,
    ) {
        val dir = Files.createTempDirectory("keyguard-keepass-test")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
