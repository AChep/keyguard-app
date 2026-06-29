package com.artemchep.keyguard.common.service.keepass

import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.common.model.WebDavCredentials
import com.artemchep.keyguard.common.model.WebDavLocation
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseMetadata
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import com.artemchep.keyguard.util.webdav.WebDavAuthorization
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class KeePassDatabasePreparationTest {
    private val fileService = FileServiceImpl()
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
    fun `new db mode overwrites target when allowed`() = runTest {
        withTempDir { dir ->
            val dbPath = dir.resolve("vault.kdbx")
            dbPath.writeBytes("existing".encodeToByteArray())
            val dbUri = dbPath.toUri().toString()

            prepareKeePassDatabase(
                fileService = fileService,
                params = params(
                    mode = AddKeePassAccountParams.Mode.New(allowOverwrite = true),
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

            assertEquals("Keyguard database", database.content.meta.name)
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

    @Test
    fun `wrong key file fails before import`() = runTest {
        withTempDir { dir ->
            val dbUri = dir.resolve("vault.kdbx").toUri().toString()
            val keyUri = dir.resolve("vault.key").apply {
                writeBytes("key-file".encodeToByteArray())
            }.toUri().toString()
            val wrongKeyUri = dir.resolve("wrong.key").apply {
                writeBytes("wrong-key-file".encodeToByteArray())
            }.toUri().toString()

            prepareKeePassDatabase(
                fileService = fileService,
                params = params(
                    mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                    dbUri = dbUri,
                    keyUri = keyUri,
                    password = "secret",
                ),
            )

            assertFails {
                prepareKeePassDatabase(
                    fileService = fileService,
                    params = params(
                        mode = AddKeePassAccountParams.Mode.Open,
                        dbUri = dbUri,
                        keyUri = wrongKeyUri,
                        password = "secret",
                    ),
                )
            }
        }
    }

    @Test
    fun `missing key file fails before import`() = runTest {
        withTempDir { dir ->
            val dbUri = dir.resolve("vault.kdbx").toUri().toString()
            val keyUri = dir.resolve("missing.key").toUri().toString()

            assertFails {
                prepareKeePassDatabase(
                    fileService = fileService,
                    params = params(
                        mode = AddKeePassAccountParams.Mode.Open,
                        dbUri = dbUri,
                        keyUri = keyUri,
                        password = "secret",
                    ),
                )
            }
        }
    }

    @Test
    fun `webdav new db mode overwrites target when allowed`() = runTest {
        val webDavClientFactory = FakeKeePassWebDavClientFactory()
        val dbUri = "https://example.com/dav/vault.kdbx"
        webDavClientFactory.client.putObject(
            path = "vault.kdbx",
            bytes = "existing".encodeToByteArray(),
        )

        prepareKeePassDatabase(
            fileService = fileService,
            params = params(
                mode = AddKeePassAccountParams.Mode.New(allowOverwrite = true),
                dbUri = dbUri,
                password = "secret",
                webDav = webDavFile(dbUri),
            ),
            webDavClientFactory = webDavClientFactory,
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
                    webDav = KeePassToken.Files.WebDav(
                        username = null,
                    ),
                ),
            ),
            fileService = fileService,
            base64Service = base64Service,
            webDavClientFactory = webDavClientFactory,
        )

        assertEquals("Keyguard database", database.content.meta.name)
        assertEquals(1, webDavClientFactory.client.writeCount)
    }

    @Test
    fun `webdav new db mode creates a readable database`() = runTest {
        val webDavClientFactory = FakeKeePassWebDavClientFactory()
        val dbUri = "https://example.com/dav/vault.kdbx"

        prepareKeePassDatabase(
            fileService = fileService,
            params = params(
                mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                dbUri = dbUri,
                password = "secret",
                webDav = webDavFile(
                    url = dbUri,
                    username = "alice",
                    password = "server-password",
                ),
            ),
            webDavClientFactory = webDavClientFactory,
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
                    webDav = KeePassToken.Files.WebDav(
                        username = "alice",
                    ),
                ),
            ),
            fileService = fileService,
            base64Service = base64Service,
            webDavClientFactory = webDavClientFactory,
        )

        assertNotNull(database)
    }

    @Test
    fun `webdav new db mode refuses overwrite when target exists`() = runTest {
        val webDavClientFactory = FakeKeePassWebDavClientFactory()
        val dbUri = "https://example.com/dav/vault.kdbx"
        val params = params(
            mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
            dbUri = dbUri,
            password = "secret",
            webDav = webDavFile(dbUri),
        )
        prepareKeePassDatabase(
            fileService = fileService,
            params = params,
            webDavClientFactory = webDavClientFactory,
        )

        assertFails {
            prepareKeePassDatabase(
                fileService = fileService,
                params = params,
                webDavClientFactory = webDavClientFactory,
            )
        }
    }

    @Test
    fun `webdav url strips query and fragment and decodes filename`() = runTest {
        val webDavClientFactory = FakeKeePassWebDavClientFactory()
        val dbUri = "https://example.com/dav/folder/vault%20file.kdbx?download=1#section"

        prepareKeePassDatabase(
            fileService = fileService,
            params = params(
                mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                dbUri = dbUri,
                password = "secret",
                webDav = webDavFile(dbUri),
            ),
            webDavClientFactory = webDavClientFactory,
        )

        val config = webDavClientFactory.configs.single()
        assertEquals("https://example.com/dav/folder/", config.baseUrl)
        assertTrue(config.noCache)
        assertNotNull(webDavClientFactory.client.readObjectBytes("vault file.kdbx"))
    }

    @Test
    fun `webdav url rejects trailing directory`() = runTest {
        val webDavClientFactory = FakeKeePassWebDavClientFactory()

        assertFails {
            prepareKeePassDatabase(
                fileService = fileService,
                params = params(
                    mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                    dbUri = "https://example.com/dav/folder/",
                    password = "secret",
                    webDav = webDavFile("https://example.com/dav/folder/"),
                ),
                webDavClientFactory = webDavClientFactory,
            )
        }

        assertTrue(webDavClientFactory.configs.isEmpty())
    }

    @Test
    fun `webdav blank username creates no authorization`() = runTest {
        val webDavClientFactory = FakeKeePassWebDavClientFactory()

        prepareKeePassDatabase(
            fileService = fileService,
            params = params(
                mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                dbUri = "https://example.com/dav/vault.kdbx",
                password = "secret",
                webDav = webDavFile(
                    url = "https://example.com/dav/vault.kdbx",
                    username = " ",
                    password = "ignored",
                ),
            ),
            webDavClientFactory = webDavClientFactory,
        )

        assertNull(webDavClientFactory.configs.single().authorization)
    }

    @Test
    fun `webdav username and password create basic authorization`() = runTest {
        val webDavClientFactory = FakeKeePassWebDavClientFactory()

        prepareKeePassDatabase(
            fileService = fileService,
            params = params(
                mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                dbUri = "https://example.com/dav/vault.kdbx",
                password = "secret",
                webDav = webDavFile(
                    url = "https://example.com/dav/vault.kdbx",
                    username = " alice ",
                    password = "server-password",
                ),
            ),
            webDavClientFactory = webDavClientFactory,
        )

        val auth = assertIs<WebDavAuthorization.Basic>(
            webDavClientFactory.configs.single().authorization,
        )
        assertEquals("alice", auth.username)
        assertEquals("server-password", auth.password)
    }

    @Test
    fun `webdav token password is used for basic authorization`() = runTest {
        val webDavClientFactory = FakeKeePassWebDavClientFactory()
        val dbUri = "https://example.com/dav/vault.kdbx"
        prepareKeePassDatabase(
            fileService = fileService,
            params = params(
                mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                dbUri = dbUri,
                password = "secret",
                webDav = webDavFile(
                    url = dbUri,
                    username = "alice",
                    password = "initial-password",
                ),
            ),
            webDavClientFactory = webDavClientFactory,
        )

        openKeePassDatabase(
            token = KeePassToken(
                id = "account-id",
                key = KeePassToken.Key(
                    passwordBase64 = base64Service.encodeToString("secret"),
                    keyBase64 = null,
                ),
                files = KeePassToken.Files(
                    databaseUri = dbUri,
                    databaseFileName = "vault.kdbx",
                    webDav = KeePassToken.Files.WebDav(
                        username = "alice",
                        password = com.artemchep.keyguard.common.model.Password("token-password"),
                    ),
                ),
            ),
            fileService = fileService,
            base64Service = base64Service,
            webDavClientFactory = webDavClientFactory,
        )

        val auth = assertIs<WebDavAuthorization.Basic>(
            webDavClientFactory.configs.last().authorization,
        )
        assertEquals("alice", auth.username)
        assertEquals("token-password", auth.password)
    }

    @Test
    fun `webdav collection stat is ignored as database file`() = runTest {
        val webDavClientFactory = FakeKeePassWebDavClientFactory()
        webDavClientFactory.client.putCollection("vault.kdbx")

        val metadata = getKeePassDatabaseMetadata(
            fileService = fileService,
            token = KeePassToken(
                id = "account-id",
                key = KeePassToken.Key(
                    passwordBase64 = base64Service.encodeToString("secret"),
                    keyBase64 = null,
                ),
                files = KeePassToken.Files(
                    databaseUri = "https://example.com/dav/vault.kdbx",
                    databaseFileName = "vault.kdbx",
                    webDav = KeePassToken.Files.WebDav(
                        username = null,
                    ),
                ),
            ),
            webDavClientFactory = webDavClientFactory,
        )

        assertNull(metadata)
    }

    @Test
    fun `metadata compares etag when both sides provide etag`() {
        val left = KeePassDatabaseMetadata(
            etag = "etag-1",
            lastModified = Instant.parse("2024-01-01T00:00:00Z"),
            size = 10,
        )
        val right = KeePassDatabaseMetadata(
            etag = "etag-2",
            lastModified = left.lastModified,
            size = left.size,
        )

        assertTrue(left.isComparableWith(right))
        assertTrue(left.differsFrom(right))
        assertFalse(left.differsFrom(right.copy(etag = left.etag)))
    }

    @Test
    fun `metadata compares last modified and size when etag is unavailable`() {
        val left = KeePassDatabaseMetadata(
            etag = null,
            lastModified = Instant.parse("2024-01-01T00:00:00Z"),
            size = 10,
        )
        val same = left.copy()
        val changedTimestamp = left.copy(
            lastModified = Instant.parse("2024-01-02T00:00:00Z"),
        )
        val changedSize = left.copy(size = 11)

        assertTrue(left.isComparableWith(same))
        assertFalse(left.differsFrom(same))
        assertTrue(left.differsFrom(changedTimestamp))
        assertTrue(left.differsFrom(changedSize))
    }

    @Test
    fun `metadata without shared comparable fields is not comparable`() {
        val left = KeePassDatabaseMetadata(
            etag = null,
            lastModified = Instant.parse("2024-01-01T00:00:00Z"),
            size = null,
        )
        val right = KeePassDatabaseMetadata(
            etag = null,
            lastModified = null,
            size = 10,
        )

        assertFalse(left.isComparableWith(right))
        assertFalse(left.differsFrom(right))
    }

    @Test
    fun `local non file uri metadata is unavailable`() = runTest {
        val metadata = getKeePassDatabaseMetadata(
            fileService = fileService,
            token = KeePassToken(
                id = "account-id",
                key = KeePassToken.Key(
                    passwordBase64 = base64Service.encodeToString("secret"),
                    keyBase64 = null,
                ),
                files = KeePassToken.Files(
                    databaseUri = "content://vault.kdbx",
                    databaseFileName = "vault.kdbx",
                ),
            ),
        )

        assertNull(metadata)
    }

    private fun params(
        mode: AddKeePassAccountParams.Mode,
        dbUri: String,
        password: String,
        keyUri: String? = null,
        webDav: WebDavLocation.File? = null,
    ) = AddKeePassAccountParams(
        mode = mode,
        dbUri = dbUri,
        dbFileName = "vault.kdbx",
        webDav = webDav,
        keyUri = keyUri,
        password = password,
    )

    private fun webDavFile(
        url: String,
        username: String? = null,
        password: String? = null,
    ) = WebDavLocation.File(
        url = url,
        credentials = WebDavCredentials.of(
            username = username,
            password = password,
        ),
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
