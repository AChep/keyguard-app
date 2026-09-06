package com.artemchep.keyguard.common.service.keepass

import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class KeePassDatabaseKeyFileOnlyTest {
    private val fileService = FileServiceImpl()
    private val base64Service = Base64ServiceJvm()

    @Test
    fun `key file only db opens without a password`() = runTest {
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
                    password = "",
                ),
            )
            val keyData = assertNotNull(created.keyData)

            // Re-open through the persisted token, the way
            // the app does it after the account is added.
            val database = openKeePassDatabase(
                token = KeePassToken(
                    id = "account-id",
                    key = KeePassToken.Key(
                        passwordBase64 = "",
                        keyBase64 = base64Service.encodeToString(keyData),
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

            // An empty password must be treated as "no password",
            // not as a password of zero length.
            assertFails {
                openKeePassDatabase(
                    token = KeePassToken(
                        id = "account-id",
                        key = KeePassToken.Key(
                            passwordBase64 = base64Service.encodeToString("secret"),
                            keyBase64 = base64Service.encodeToString(keyData),
                        ),
                        files = KeePassToken.Files(
                            databaseUri = dbUri,
                            databaseFileName = "vault.kdbx",
                        ),
                    ),
                    fileService = fileService,
                    base64Service = base64Service,
                )
            }
        }
    }

    @Test
    fun `empty password without key file is rejected`() = runTest {
        withTempDir { dir ->
            val dbUri = dir.resolve("vault.kdbx").toUri().toString()
            assertFailsWith<IllegalArgumentException> {
                prepareKeePassDatabase(
                    fileService = fileService,
                    params = params(
                        mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                        dbUri = dbUri,
                        password = "",
                    ),
                )
            }
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
        webDav = null,
        keyUri = keyUri,
        password = password,
    )

    private inline fun withTempDir(
        block: (Path) -> Unit,
    ) {
        val dir = Files.createTempDirectory("keyguard-keepass-test")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
