package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.agent.AgentCallerAuthorizationSchema
import com.artemchep.keyguard.common.service.agent.CallerAuthorization
import com.artemchep.keyguard.common.service.agent.CallerAuthorizationSubject
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.ManageResponse
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.Request
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.Response
import com.artemchep.keyguard.common.service.cli.CliJson
import com.artemchep.keyguard.common.service.cli.cliSuccessBody
import com.artemchep.keyguard.common.service.environment.EnvironmentRunCliRequest
import com.artemchep.keyguard.common.service.environment.EnvironmentRunCliResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Checks the encodings in `desktopCliAgent/proto/fixtures/`, which the Rust
 * tests check as well: each fixture decodes to the message it stands for.
 * Kotlin's serializers encode with the same field numbers they decode with.
 */
@OptIn(ExperimentalSerializationApi::class)
class CliAgentProtocolTest {
    @Test
    fun requestsDecodeFromTheSharedFixtures() {
        check("requests.pbhex", requests().mapValues { (_, request) -> Request(request) })
        // An absent value keeps the variable as it is; an empty one is set.
        val fixture = fixtures("requests.pbhex").getValue("manage").hexToByteArray()
        val manage = ProtoBuf.decodeFromByteArray<Request>(fixture).request as Request.Kind.Manage
        assertEquals(listOf(6, 0, null), manage.value.variables.map { it.value?.size })
    }

    @Test
    fun responsesDecodeFromTheSharedFixtures() {
        check("responses.pbhex", responses().mapValues { (_, response) -> Response(response) })
    }

    /** The JSON of `keyguard run` in the fixtures is the command as Kotlin reads and writes it. */
    @Test
    fun runFixturesHoldTheCommandAsJson() {
        for ((fixture, account) in listOf("cli_run" to null, "cli_run_in_account" to "Work")) {
            val bytes = fixtures("requests.pbhex").getValue(fixture).hexToByteArray()
            val cli = (ProtoBuf.decodeFromByteArray<Request>(bytes).request as Request.Kind.Cli).value
            assertEquals("run", cli.command)
            assertEquals(
                EnvironmentRunCliRequest(
                    environment = "Production",
                    account = account,
                    command = listOf("npm", "start"),
                    workingDirectory = "/work",
                ),
                CliJson.decodeFromString(EnvironmentRunCliRequest.serializer(), cli.request.decodeToString()),
            )
        }
        val bytes = fixtures("responses.pbhex").getValue("cli_run").hexToByteArray()
        val cli = (ProtoBuf.decodeFromByteArray<Response>(bytes).response as Response.Kind.Cli).value
        assertEquals(
            """{"ok":true,"variables":{"TOKEN":"secret","EMPTY":""}}""",
            cli.result.decodeToString(),
        )
        assertContentEquals(cli.result, cliSuccessBody(EnvironmentRunCliResult.serializer(), runResult))
    }

    /** The messages of the fixtures, by name, as the Rust tests build them. */
    private fun requests() = linkedMapOf(
        "authenticate" to Request.Kind.Authenticate(
            CliAgentMessages.AuthenticateRequest(token = ByteArray(32) { 0x11 }, protocolRevision = 1),
        ),
        "manage" to Request.Kind.Manage(
            CliAgentMessages.ManageRequest(
                caller = verifiedCaller(),
                connectionId = ByteArray(32) { 0x44 },
                clientName = "Claude Code",
                tool = "append_variables",
                arguments = mapOf("account_id" to "a", "environment_id" to "e"),
                variables = listOf(
                    CliAgentMessages.VariableUpdate("TOKEN", "secret".encodeToByteArray()),
                    CliAgentMessages.VariableUpdate("EMPTY", byteArrayOf()),
                    CliAgentMessages.VariableUpdate("UNSET", null),
                ),
            ),
        ),
        "manage_unverified" to Request.Kind.Manage(
            CliAgentMessages.ManageRequest(
                caller = CallerIdentity(pid = 7, processName = "node.exe"),
                connectionId = ByteArray(32) { 0x44 },
                tool = "authenticate",
            ),
        ),
        "mounts" to Request.Kind.Mounts(CliAgentMessages.Empty),
        "watch_mounts" to Request.Kind.WatchMounts(CliAgentMessages.WatchMountsRequest("r1")),
        "mount_status" to Request.Kind.MountStatus(
            CliAgentMessages.MountStatusRequest("m", CliAgentMessages.MountStatus.READY),
        ),
        "resolve_mount" to Request.Kind.ResolveMount(CliAgentMessages.ResolveMountRequest("m", "g")),
        "cli_run" to run(account = null),
        "cli_run_in_account" to run(account = "Work"),
    )

    private fun responses() = linkedMapOf(
        "authenticate" to Response.Kind.Authenticate(CliAgentMessages.Empty),
        "error" to Response.Kind.Error(CliAgentMessages.ErrorResponse("user_denied")),
        "manage_accounts" to manage(
            ManageResponse.Result.Accounts(
                CliAgentMessages.AccountList(listOf(CliAgentMessages.Account("a", "Personal"))),
            ),
        ),
        "manage_environments" to manage(
            ManageResponse.Result.Environments(
                CliAgentMessages.EnvironmentList(
                    listOf(
                        CliAgentMessages.Environment("e", "a", "Production", variableCount = 2, editable = true),
                    ),
                ),
            ),
        ),
        "manage_environment" to manage(ManageResponse.Result.Environment(CliAgentMessages.EnvironmentId("e", "a"))),
        "manage_variables" to manage(
            ManageResponse.Result.Variables(
                CliAgentMessages.VariableList(
                    listOf(
                        CliAgentMessages.Variable("TOKEN", CliAgentMessages.VariableSource.REFERENCE, true, true),
                        CliAgentMessages.Variable("UNSET", CliAgentMessages.VariableSource.UNSET, false, true),
                    ),
                ),
            ),
        ),
        "manage_local_env_file" to manage(
            ManageResponse.Result.LocalEnvFile(localEnvFile(CliAgentMessages.MountStatus.READY)),
        ),
        "manage_local_env_files" to manage(
            ManageResponse.Result.LocalEnvFiles(
                CliAgentMessages.LocalEnvFileList(listOf(localEnvFile(CliAgentMessages.MountStatus.PENDING))),
            ),
        ),
        "mounts" to Response.Kind.Mounts(
            CliAgentMessages.MountsResponse(
                revision = "r1",
                mounts = listOf(
                    CliAgentMessages.Mount("m", "/work/.env", "g", bookmark = null),
                    CliAgentMessages.Mount("n", "/other/.env", "g", bookmark = byteArrayOf(1, 2, 3)),
                ),
            ),
        ),
        "mount_status" to Response.Kind.MountStatus(CliAgentMessages.Empty),
        "resolve_mount" to Response.Kind.ResolveMount(
            CliAgentMessages.ResolveMountResponse("g", "TOKEN='secret'\n".encodeToByteArray()),
        ),
        "cli_run" to Response.Kind.Cli(
            CliAgentMessages.CliResponse(cliSuccessBody(EnvironmentRunCliResult.serializer(), runResult)),
        ),
    )

    private val runResult = EnvironmentRunCliResult(linkedMapOf("TOKEN" to "secret", "EMPTY" to ""))

    private fun localEnvFile(status: CliAgentMessages.MountStatus) =
        CliAgentMessages.LocalEnvFile("m", "e", "/work/.env", enabled = true, status = status, accountId = "a")

    /**
     * Checks that each fixture decodes to its message. Equal messages have
     * equal Kotlin encodings, also those with byte fields. The encodings
     * themselves can differ from the fixtures: kotlinx writes the empty byte
     * fields that prost leaves out, which decode the same.
     */
    private inline fun <reified T> check(name: String, messages: Map<String, T>) {
        val fixtures = fixtures(name)
        assertEquals(fixtures.keys.toList(), messages.keys.toList(), "The same messages, in order")
        for ((fixture, message) in messages) {
            val decoded = ProtoBuf.decodeFromByteArray<T>(fixtures.getValue(fixture).hexToByteArray())
            assertEquals(
                ProtoBuf.encodeToByteArray(message).toHexString(),
                ProtoBuf.encodeToByteArray(decoded).toHexString(),
                fixture,
            )
        }
    }

    private fun verifiedCaller() = CallerIdentity(
        pid = 42,
        processName = "node",
        executablePath = "/usr/bin/node",
        appName = "Terminal",
        appBundlePath = "/Applications/Terminal.app",
        authorization = CallerAuthorization(
            connectionFingerprint = ByteArray(32) { 0x22 },
            subjects = listOf(
                CallerAuthorizationSubject(
                    kind = AgentCallerAuthorizationSchema.SubjectKind.TERMINAL_SESSION,
                    evidenceSource = AgentCallerAuthorizationSchema.EvidenceSource.MACOS_TERMINAL_SESSION,
                    fingerprint = ByteArray(32) { 0x33 },
                ),
            ),
        ),
    )

    /** A `keyguard run` request, with the JSON that `commonCli` writes: its keys are sorted. */
    private fun run(account: String?) = Request.Kind.Cli(
        CliAgentMessages.CliRequest(
            caller = verifiedCaller(),
            command = "run",
            request = buildString {
                append("{")
                if (account != null) append(""""account":"$account",""")
                append(""""command":["npm","start"],"environment":"Production","workingDirectory":"/work"}""")
            }.encodeToByteArray(),
        ),
    )

    private fun manage(result: ManageResponse.Result) = Response.Kind.Manage(ManageResponse(result))

    /** The hex of each fixture by its name, in file order. */
    private fun fixtures(name: String): Map<String, String> {
        val text = requireNotNull(javaClass.getResource("/fixtures/$name")) { "Missing fixture $name" }.readText()
        return text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val (fixture, hex) = line.split(' ', limit = 2)
                fixture to hex.trim()
            }
    }
}
