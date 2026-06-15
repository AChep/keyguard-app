package com.artemchep.keyguard.android

import com.artemchep.keyguard.android.companion.CompanionAuthTransferException
import com.artemchep.keyguard.android.companion.CompanionKeePassAddAccount
import com.artemchep.keyguard.android.companion.runCompanionTransfer
import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthError
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthProvider
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CompanionAuthAddAccountTest {
    @Test
    fun `transfer failure notifies watch and throws readable exception`() = runTest {
        var notifiedError: CompanionAuthError? = null
        var notifiedMessage: String? = null

        val error = assertFailsWith<CompanionAuthTransferException> {
            runCompanionTransfer(
                notifyError = { companionError, message ->
                    notifiedError = companionError
                    notifiedMessage = message
                },
            ) {
                error("boom")
            }
        }

        assertIs<Readable>(error)
        assertEquals(CompanionAuthError.REQUEST_FAILED, notifiedError)
        assertEquals("boom", notifiedMessage)
        assertEquals("boom", error.message)
    }

    @Test
    fun `keepass open preflight runs before transfer`() = runTest {
        val events = mutableListOf<String>()

        val addAccount = CompanionKeePassAddAccount(
            requestId = "request-id",
            preflight = { params ->
                events += "prepare:${params.mode}"
            },
            complete = { _, _, _ ->
                events += "complete"
            },
            notifyError = { _, _ ->
                events += "notify"
            },
        )

        addAccount(
            params = params(
                mode = AddKeePassAccountParams.Mode.Open,
            ),
        )()

        assertEquals(
            listOf(
                "prepare:${AddKeePassAccountParams.Mode.Open}",
                "complete",
            ),
            events,
        )
    }

    @Test
    fun `keepass new preflight runs before transfer`() = runTest {
        val events = mutableListOf<String>()

        val addAccount = CompanionKeePassAddAccount(
            requestId = "request-id",
            preflight = { params ->
                events += "prepare:${params.mode}"
            },
            complete = { _, _, _ ->
                events += "complete"
            },
            notifyError = { _, _ ->
                events += "notify"
            },
        )

        addAccount(
            params = params(
                mode = AddKeePassAccountParams.Mode.New(
                    allowOverwrite = false,
                ),
            ),
        )()

        assertEquals(
            listOf(
                "prepare:${AddKeePassAccountParams.Mode.New(allowOverwrite = false)}",
                "complete",
            ),
            events,
        )
    }

    @Test
    fun `keepass preflight failure skips transfer`() = runTest {
        var completed = false
        var notified = false

        val addAccount = CompanionKeePassAddAccount(
            requestId = "request-id",
            preflight = {
                error("invalid credentials")
            },
            complete = { _, _, _ ->
                completed = true
            },
            notifyError = { _, _ ->
                notified = true
            },
        )

        assertFailsWith<IllegalStateException> {
            addAccount(
                params = params(
                    mode = AddKeePassAccountParams.Mode.Open,
                ),
            )()
        }

        assertEquals(false, completed)
        assertEquals(false, notified)
    }

    private fun params(
        mode: AddKeePassAccountParams.Mode,
    ) = AddKeePassAccountParams(
        mode = mode,
        dbUri = "file:///vault.kdbx",
        dbFileName = "vault.kdbx",
        keyUri = null,
        password = "secret",
    )
}
