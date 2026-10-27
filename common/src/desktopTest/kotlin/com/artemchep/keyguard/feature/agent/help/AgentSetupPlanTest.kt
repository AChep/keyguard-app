package com.artemchep.keyguard.feature.agent.help

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSetupPlanTest {
    @Test
    fun rejectsPlansFromOtherHelperVersions() {
        listOf(1, 3, 5).forEach { version ->
            val error = assertFailsWith<AgentSetupException> {
                parseAgentSetupPlan("""{"version": $version}""")
            }
            assertEquals(AgentSetupError.Kind.UPDATE_REQUIRED, error.error.kind)
        }
    }

    @Test
    fun rejectsConflictCodesFromOtherHelperVersions() {
        val raw = """
            {
              "version": 4,
              "undo": false,
              "edits": [],
              "conflicts": [{"code": "newer", "path": "/home/user/.zshrc"}],
              "overrides": []
            }
        """.trimIndent()
        val error = assertFailsWith<AgentSetupException> { parseAgentSetupPlan(raw) }
        assertEquals(AgentSetupError.Kind.UPDATE_REQUIRED, error.error.kind)
    }

    @Test
    fun readsHelperErrorCodesAndKeepsOtherOutputAsDetail() {
        val unsafe = """{"code":"unsafe_file","path":"/home/user/.zshrc","detail":"cannot safely edit"}"""
        assertEquals(
            AgentSetupError(AgentSetupError.Kind.UNSAFE_FILE, "/home/user/.zshrc", "cannot safely edit"),
            parseAgentSetupError("warning\n$unsafe\n"),
        )
        assertEquals(
            AgentSetupError(AgentSetupError.Kind.BUSY, detail = "busy"),
            parseAgentSetupError("""{"code":"busy","detail":"busy"}"""),
        )
        assertEquals(
            AgentSetupError(AgentSetupError.Kind.FAILED, detail = "ssh-agent: no such file"),
            parseAgentSetupError("ssh-agent: no such file\n"),
        )
        assertEquals(
            AgentSetupError(AgentSetupError.Kind.FAILED, detail = """{"code":"newer"}"""),
            parseAgentSetupError("""{"code":"newer"}"""),
        )
        assertEquals(AgentSetupError(AgentSetupError.Kind.FAILED), parseAgentSetupError(""))
    }

    @Test
    fun preservesExactPlanAndDistinguishesEmptyFromMissingFiles() {
        val raw = """
            {
              "version": 4,
              "undo": true,
              "request": {"agent": {"kind": "ssh", "socket": "/unused"}, "shells": []},
              "edits": [
                {"path": "/home/user/.zshrc", "before": null, "after": ""},
                {"path": "/home/user/.profile", "before": "export EDITOR=vim\r\n", "after": null}
              ],
              "conflicts": [{"code": "changed_block", "path": "/home/user/.profile"}],
              "overrides": [{"setting": "GNUPGHOME", "path": "/home/user/.zlogin", "line": 7}],
              "observed_files": [{"path": "/home/user/.zshrc", "sha256": "digest retained in raw plan"}]
            }
        """.trimIndent()
        val plan = parseAgentSetupPlan(raw)
        assertEquals(raw, plan.json)
        assertTrue(plan.undo)
        assertEquals(
            AgentSetupConflict(AgentSetupConflict.Kind.CHANGED_BLOCK, "/home/user/.profile"),
            plan.conflicts.single(),
        )
        assertEquals(
            AgentSetupOverride("GNUPGHOME", "/home/user/.zlogin", 7),
            plan.overrides.single(),
        )
        assertNull(plan.edits[0].before)
        assertEquals("", plan.edits[0].after)
        assertEquals("export EDITOR=vim\r\n", plan.edits[1].before)
        assertNull(plan.edits[1].after)
    }
}
