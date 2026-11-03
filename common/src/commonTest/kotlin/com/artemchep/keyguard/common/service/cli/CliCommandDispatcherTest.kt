package com.artemchep.keyguard.common.service.cli

import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.cliRequest
import com.artemchep.keyguard.common.service.cli.agent.cliResult
import com.artemchep.keyguard.common.service.cli.agent.error
import com.artemchep.keyguard.common.service.cli.agent.runRequest
import com.artemchep.keyguard.common.service.environment.EnvironmentFixture
import com.artemchep.keyguard.common.service.environment.EnvironmentRunCliRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CliCommandDispatcherTest {
    @Serializable
    private data class Answer(val value: String = "x")

    /** A result that names the field the dispatcher adds. */
    @Serializable
    private data class OkAnswer(val ok: Boolean = false)

    /** Approves the request, as `CliServiceImpl.approve` would, unless it must not [approve]. */
    private class Handler<R : Any>(
        override val resultSerializer: KSerializer<R>,
        private val approve: Boolean = true,
        private val block: suspend (CliCallContext, EnvironmentRunCliRequest) -> R,
    ) : CliCommandHandler<EnvironmentRunCliRequest, R> {
        override val command = CliCommand.Run
        override val requestSerializer = EnvironmentRunCliRequest.serializer()

        override suspend fun handle(context: CliCallContext, request: EnvironmentRunCliRequest): R {
            if (approve) context.approved = true
            return block(context, request)
        }
    }

    private fun handler(
        approve: Boolean = true,
        block: suspend (CliCallContext) -> Answer = { Answer() },
    ) = Handler(Answer.serializer(), approve) { context, _ -> block(context) }

    private suspend fun EnvironmentFixture.readyCli() {
        ready()
        cli.configureRuntime(path = "/bin/keyguard", capabilities = UNSANDBOXED)
        cli.setEnabled(true)
        cli.state.first { it.enabled }
    }

    @Test
    fun `gates apply in order before any command runs`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope, initiallyLocked = true)
        var calls = 0
        val dispatcher = CliCommandDispatcher(fixture.cli, listOf(handler { calls++; Answer() }))
        assertEquals("unsupported_platform", dispatcher.handle(runRequest()).error())
        fixture.cli.configureRuntime(path = "/bin/keyguard", capabilities = emptySet())
        assertEquals("integration_disabled", dispatcher.handle(runRequest()).error())
        fixture.cli.setEnabled(true)
        // A command of a newer agent.
        assertEquals("unsupported_command", dispatcher.handle(runRequest(name = "read")).error())
        // The command requires a capability that the platform lacks.
        assertEquals("unsupported_platform", dispatcher.handle(runRequest()).error())
        fixture.cli.configureRuntime(path = "/bin/keyguard", capabilities = UNSANDBOXED)
        assertEquals("vault_locked", dispatcher.handle(runRequest()).error())
        fixture.unlock()
        assertEquals(0, calls)
        assertEquals(ANSWER, dispatcher.handle(runRequest()).cliResult())
        assertEquals(1, calls)
    }

    @Test
    fun `a command that no handler knows is unsupported`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        val dispatcher = CliCommandDispatcher(fixture.cli, emptyList())
        assertEquals("unsupported_command", dispatcher.handle(runRequest()).error())
    }

    @Test
    fun `a command that requires an approval never answers without one`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        var calls = 0
        val dispatcher = CliCommandDispatcher(fixture.cli, listOf(handler(approve = false) { calls++; Answer() }))
        assertEquals("operation_failed", dispatcher.handle(runRequest()).error())
        assertEquals(1, calls)
    }

    @Test
    fun `each command has one handler`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        assertFailsWith<IllegalArgumentException> {
            CliCommandDispatcher(fixture.cli, listOf(handler(), handler()))
        }
    }

    @Test
    fun `the handler receives the decoded request`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        var received: EnvironmentRunCliRequest? = null
        val dispatcher = CliCommandDispatcher(
            fixture.cli,
            listOf(Handler(Answer.serializer()) { _, request -> received = request; Answer() }),
        )
        dispatcher.handle(runRequest(environment = "e", account = "a", command = listOf("npm", "start")))
        assertEquals(
            EnvironmentRunCliRequest(
                environment = "e",
                account = "a",
                command = listOf("npm", "start"),
                workingDirectory = "/work",
            ),
            received,
        )
    }

    @Test
    fun `a request with unknown fields or of another shape is rejected`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        var calls = 0
        val dispatcher = CliCommandDispatcher(fixture.cli, listOf(handler { calls++; Answer() }))
        for (request in listOf(
            """{"environment":"e","command":["env"],"workingDirectory":"/","extra":1}""",
            """{"environment":"e","workingDirectory":"/"}""",
            """{"environment":1,"command":["env"],"workingDirectory":"/"}""",
            "[]",
            "not json",
            "",
        )) {
            assertEquals("invalid_request", dispatcher.handle(cliRequest("run", request)).error(), request)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `a result that names ok is a failure of the command`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        val dispatcher = CliCommandDispatcher(
            fixture.cli,
            listOf(Handler(OkAnswer.serializer()) { _, _ -> OkAnswer() }),
        )
        assertEquals("operation_failed", dispatcher.handle(runRequest()).error())
    }

    @Test
    fun `the answer of a command is wrapped and the caller is passed on`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        var caller: CliCaller? = null
        val dispatcher = CliCommandDispatcher(
            fixture.cli,
            listOf(handler { context -> caller = context.caller; Answer() }),
        )
        val response = dispatcher.handle(runRequest())
        assertEquals(ANSWER, response.cliResult())
        assertEquals(CliCaller(pid = 42, processName = "keyguard"), caller)
    }

    @Test
    fun `a request without a caller is rejected`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        val dispatcher = CliCommandDispatcher(fixture.cli, listOf(handler()))
        assertEquals("invalid_request", dispatcher.handle(runRequest(caller = null)).error())
    }

    @Test
    fun `exceptions map to stable codes and never leak their messages`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        suspend fun answer(error: Exception): CliAgentMessages.Response.Kind =
            CliCommandDispatcher(fixture.cli, listOf(handler { throw error })).handle(runRequest())
        assertEquals("not_found", answer(CliServiceException("not_found")).error())
        // Only a request that does not decode is invalid. A failing check or
        // a corrupt stored value inside the command is the command's failure.
        for (error in listOf(
            IllegalStateException("secret"),
            IllegalArgumentException("secret"),
            SerializationException("secret"),
        )) {
            val response = answer(error)
            assertEquals("operation_failed", response.error(), error.toString())
            assertFalse("secret" in response.toString())
        }
    }

    @Test
    fun `turning the CLI off while a command runs withholds its answer`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.readyCli()
        val dispatcher = CliCommandDispatcher(
            fixture.cli,
            listOf(handler { fixture.cli.setEnabled(false); Answer() }),
        )
        assertEquals("integration_disabled", dispatcher.handle(runRequest()).error())
    }

    private companion object {
        /** The public success body of [Answer]: the dispatcher adds `ok`. */
        const val ANSWER = """{"ok":true,"value":"x"}"""
    }
}
