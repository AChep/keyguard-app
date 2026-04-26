package com.artemchep.keyguard.feature.auth.bitwarden

import com.artemchep.keyguard.feature.auth.common.Validated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BitwardenLoginStateProducerTest {
    @Test
    fun `hidden custom env errors do not block predefined region login`() {
        val blockedBy = getBitwardenLoginBlockedBy(
            validatedEmail = Validated.Success("test@example.com"),
            validatedPassword = Validated.Success("password123"),
            validatedClientSecret = null,
            showCustomEnv = false,
            outputError = true,
            taskIsExecuting = false,
        )

        assertNull(blockedBy)
    }

    @Test
    fun `valid default login remains enabled`() {
        val blockedBy = getBitwardenLoginBlockedBy(
            validatedEmail = Validated.Success("test@example.com"),
            validatedPassword = Validated.Success("password123"),
            validatedClientSecret = null,
            showCustomEnv = false,
            outputError = false,
            taskIsExecuting = false,
        )

        assertNull(blockedBy)
    }

    @Test
    fun `custom env error blocks custom env login`() {
        val blockedBy = getBitwardenLoginBlockedBy(
            validatedEmail = Validated.Success("test@example.com"),
            validatedPassword = Validated.Success("password123"),
            validatedClientSecret = null,
            showCustomEnv = true,
            outputError = true,
            taskIsExecuting = false,
        )

        assertEquals(BitwardenLoginBlockedBy.CUSTOM_ENV, blockedBy)
    }
}
