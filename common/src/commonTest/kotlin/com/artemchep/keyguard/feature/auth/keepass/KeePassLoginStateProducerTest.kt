package com.artemchep.keyguard.feature.auth.keepass

import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.Validated
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeePassLoginStateProducerTest {
    @Test
    fun `valid mode file and password create action`() {
        val dbFile = KeePassLoginState.FileItem.File(
            uri = "content://db",
            name = "vault.kdbx",
            size = 123L,
        )
        val keyFile = KeePassLoginState.FileItem.File(
            uri = "content://key",
            name = "vault.key",
            size = 12L,
        )

        var submittedMode: String? = null
        var submittedDbFile: KeePassLoginState.FileItem.File? = null
        var submittedKeyFile: KeePassLoginState.FileItem.File? = null
        var submittedWebDav: KeePassLoginState.WebDav? = null
        var submittedPassword: String? = null

        val action = createKeePassLoginAction(
            mode = "open",
            dbFile = dbFile,
            keyFile = keyFile,
            webDav = null,
            passwordValidated = Validated.Success("secret"),
        ) { mode, actionDbFile, actionKeyFile, actionWebDav, _, password ->
            submittedMode = mode
            submittedDbFile = actionDbFile
            submittedKeyFile = actionKeyFile
            submittedWebDav = actionWebDav
            submittedPassword = password
        }

        assertNotNull(action)
        action.onClick()
        assertEquals("open", submittedMode)
        assertEquals(dbFile, submittedDbFile)
        assertEquals(keyFile, submittedKeyFile)
        assertEquals(null, submittedWebDav)
        assertEquals("secret", submittedPassword)
    }

    @Test
    fun `webdav config is forwarded to action`() {
        val dbFile = KeePassLoginState.FileItem.File(
            uri = "https://example.com/dav/vault.kdbx",
            name = "vault.kdbx",
            size = null,
        )
        val webDav = KeePassLoginState.WebDav(
            url = dbFile.uri,
            username = "alice",
            password = "secret",
        )
        var submittedWebDav: KeePassLoginState.WebDav? = null

        val action = createKeePassLoginAction(
            mode = "open",
            dbFile = dbFile,
            keyFile = null,
            webDav = webDav,
            passwordValidated = Validated.Success("db-password"),
        ) { _, _, _, actionWebDav, _, _ ->
            submittedWebDav = actionWebDav
        }

        assertNotNull(action)
        action.onClick()
        assertEquals(webDav, submittedWebDav)
    }

    @Test
    fun `webdav file keeps encoded uri and exposes decoded name`() {
        val webDav = KeePassLoginState.WebDav(
            url = "https://example.com/dav/vault%20%E2%9C%93%20%25%23.kdbx?download=1#section",
            username = null,
            password = null,
        )

        val file = webDav.toKeePassLoginFile()

        assertEquals(webDav.url, file.uri)
        assertEquals("vault ✓ %#.kdbx", file.name)
        assertNull(file.size)
    }

    @Test
    fun `s3 config is forwarded to action`() {
        val s3 = KeePassLoginState.S3(
            endpoint = "https://minio.lan:9000",
            region = null,
            bucket = "vaults",
            key = "dir/vault.kdbx",
            accessKeyId = "AKID",
            secretAccessKey = "secret",
            pathStyle = true,
        )
        var submittedS3: KeePassLoginState.S3? = null

        val action = createKeePassLoginAction(
            mode = "open",
            dbFile = s3.toKeePassLoginFile(),
            keyFile = null,
            webDav = null,
            s3 = s3,
            passwordValidated = Validated.Success("db-password"),
        ) { _, _, _, _, actionS3, _ ->
            submittedS3 = actionS3
        }

        assertNotNull(action)
        action.onClick()
        assertEquals(s3, submittedS3)
    }

    @Test
    fun `s3 file exposes the object name and round trips the location`() {
        val s3 = KeePassLoginState.S3(
            endpoint = null,
            region = "eu-west-1",
            bucket = "vaults",
            key = "dir/vault.kdbx",
            accessKeyId = "AKID",
            secretAccessKey = "secret",
            pathStyle = false,
        )

        val file = s3.toKeePassLoginFile()

        assertEquals("s3://vaults/dir/vault.kdbx", file.uri)
        assertEquals("vault.kdbx", file.name)
        assertEquals(s3, s3.toS3Location().toKeePassLoginS3())
    }

    @Test
    fun `empty password with key file creates action`() {
        var submittedPassword: String? = null

        val action = createKeePassLoginAction(
            mode = "open",
            dbFile = KeePassLoginState.FileItem.File(
                uri = "content://db",
                name = "vault.kdbx",
                size = 123L,
            ),
            keyFile = KeePassLoginState.FileItem.File(
                uri = "content://key",
                name = "vault.key",
                size = 12L,
            ),
            webDav = null,
            passwordValidated = Validated.Success(""),
        ) { _, _, _, _, _, password ->
            submittedPassword = password
        }

        assertNotNull(action)
        action.onClick()
        assertEquals("", submittedPassword)
    }

    @Test
    fun `invalid password does not create action`() {
        val action = createKeePassLoginAction(
            mode = "open",
            dbFile = KeePassLoginState.FileItem.File(
                uri = "content://db",
                name = "vault.kdbx",
                size = 123L,
            ),
            keyFile = null,
            webDav = null,
            passwordValidated = Validated.Failure(
                model = "",
                error = "Must not be blank",
            ),
        ) { _, _, _, _, _, _ ->
            error("Should not submit invalid KeePass credentials")
        }

        assertNull(action)
    }

    @Test
    fun `keePass state reflects loading flag`() {
        val state = createKeePassLoginState(
            sideEffects = KeePassLoginState.SideEffect(),
            dbFileState = MutableStateFlow(
                KeePassLoginState.FileItem(
                    onClick = {},
                ),
            ),
            keyFileState = MutableStateFlow(
                KeePassLoginState.FileItem(
                    onClick = {},
                ),
            ),
            databaseLocationState = MutableStateFlow(
                KeePassLoginState.DatabaseLocation(
                    type = KeePassLoginState.DatabaseLocation.Type.Local,
                    items = persistentListOf(),
                ),
            ),
            password = MutableStateFlow(
                TextFieldModel(
                    text = "",
                    onChange = { _ -> },
                ),
            ),
            actionState = MutableStateFlow<KeePassLoginState.Action?>(null),
            tabsState = MutableStateFlow(
                KeePassLoginState.Tabs(
                    items = persistentListOf(),
                ),
            ),
            isLoading = true,
        )

        assertTrue(state.isLoading)
        assertFalse(
            createKeePassLoginState(
                sideEffects = state.sideEffects,
                dbFileState = state.dbFileState,
                keyFileState = state.keyFileState,
                databaseLocationState = state.databaseLocationState,
                password = state.password,
                actionState = state.actionState,
                tabsState = state.tabsState,
                isLoading = false,
            ).isLoading,
        )
    }
}
