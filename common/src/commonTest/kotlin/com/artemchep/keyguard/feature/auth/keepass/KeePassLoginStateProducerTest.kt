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
        ) { mode, actionDbFile, actionKeyFile, actionWebDav, password ->
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
        ) { _, _, _, actionWebDav, _ ->
            submittedWebDav = actionWebDav
        }

        assertNotNull(action)
        action.onClick()
        assertEquals(webDav, submittedWebDav)
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
        ) { _, _, _, _, _ ->
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
