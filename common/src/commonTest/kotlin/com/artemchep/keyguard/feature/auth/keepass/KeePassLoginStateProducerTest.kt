package com.artemchep.keyguard.feature.auth.keepass

import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
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
        var submittedPassword: String? = null

        val action = createKeePassLoginAction(
            mode = "open",
            dbFile = dbFile,
            keyFile = keyFile,
            passwordValidated = Validated.Success("secret"),
        ) { mode, actionDbFile, actionKeyFile, password ->
            submittedMode = mode
            submittedDbFile = actionDbFile
            submittedKeyFile = actionKeyFile
            submittedPassword = password
        }

        assertNotNull(action)
        action.onClick()
        assertEquals("open", submittedMode)
        assertEquals(dbFile, submittedDbFile)
        assertEquals(keyFile, submittedKeyFile)
        assertEquals("secret", submittedPassword)
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
            passwordValidated = Validated.Failure(
                model = "",
                error = "Must not be blank",
            ),
        ) { _, _, _, _ ->
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
            password = MutableStateFlow(
                TextFieldModel2(
                    text = "",
                    state = mutableStateOf(""),
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
                password = state.password,
                actionState = state.actionState,
                tabsState = state.tabsState,
                isLoading = false,
            ).isLoading,
        )
    }
}
