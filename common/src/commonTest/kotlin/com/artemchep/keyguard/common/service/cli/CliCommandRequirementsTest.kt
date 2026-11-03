package com.artemchep.keyguard.common.service.cli

import com.artemchep.keyguard.common.service.cli.CliCommandRequirements.Authorization
import com.artemchep.keyguard.common.service.cli.CliCommandRequirements.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliCommandRequirementsTest {
    private val bundled = CliServiceState(path = "/bin/keyguard")

    private fun requirements(
        session: Session = Session.Any,
        platform: Set<CliPlatformCapability> = emptySet(),
        authorization: Authorization = Authorization.None,
    ) = CliCommandRequirements(session, platform, authorization)

    /** The error code of the check, `null` if the command may run. */
    private fun CliServiceState.rejection(requirements: CliCommandRequirements, unlocked: Boolean = true) = try {
        checkRequirements(requirements, unlocked)
        null
    } catch (e: CliServiceException) {
        e.code
    }

    @Test
    fun `a command that allows a locked vault answers while it is locked`() {
        assertEquals(null, bundled.rejection(requirements(), unlocked = false))
        assertEquals("vault_locked", bundled.rejection(requirements(session = Session.Unlocked), unlocked = false))
        assertEquals(null, bundled.rejection(requirements(session = Session.Unlocked)))
    }

    @Test
    fun `a command runs only where the platform lets the CLI do what it requires`() {
        val unsandboxed = requirements(platform = UNSANDBOXED)
        assertEquals("unsupported_platform", bundled.rejection(unsandboxed))
        assertEquals(null, bundled.copy(capabilities = UNSANDBOXED).rejection(unsandboxed))
        // Without a bundled CLI, no command runs.
        val unbundled = CliServiceState(capabilities = UNSANDBOXED)
        assertEquals("unsupported_platform", unbundled.rejection(requirements()))
    }

    @Test
    fun `only a command that requires an approval needs one`() {
        val approval = requirements(session = Session.Unlocked, authorization = Authorization.Approval)
        val context = CliCallContext(caller = CliCaller(), terminalSession = null)
        context.checkAuthorized(requirements())
        assertFailsWith<IllegalStateException> { context.checkAuthorized(approval) }
        context.approved = true
        context.checkAuthorized(approval)
    }

    @Test
    fun `a command that requires an approval requires an unlocked vault`() {
        assertFailsWith<IllegalArgumentException> {
            requirements(authorization = Authorization.Approval)
        }
    }
}
