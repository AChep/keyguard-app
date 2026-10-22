package com.artemchep.keyguard.common.service.agent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.assertEquals

/**
 * The golden fixtures of the agents' private channels, which their Rust tests
 * check as well. A fixture file holds one `<name> <hex>` per line, and `#`
 * starts a comment line. The test resources hold each crate's `fixtures/`.
 */
@OptIn(ExperimentalSerializationApi::class)
object AgentFixtures {
    /** The bytes of each fixture in [file], by its name, in file order. */
    fun read(file: String): LinkedHashMap<String, ByteArray> {
        val text = requireNotNull(javaClass.getResource("/fixtures/$file")) {
            "Missing fixture file $file"
        }.readText()
        return text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associateTo(LinkedHashMap()) { line ->
                val (name, hex) = line.split(' ', limit = 2)
                name to hex.trim().hexToByteArray()
            }
    }

    /**
     * Checks that each fixture in [file] decodes to its message, in order.
     * Equal messages have equal Kotlin encodings, also those with byte
     * fields. The encodings themselves can differ from the fixtures: kotlinx
     * writes the empty byte fields that prost leaves out, which decode the
     * same. With [exact], Kotlin must also write the fixture's very bytes, as
     * it should for the messages it sends.
     */
    inline fun <reified T> check(
        file: String,
        messages: Map<String, T>,
        exact: Boolean = false,
    ) {
        val fixtures = read(file)
        assertEquals(fixtures.keys.toList(), messages.keys.toList(), "The same messages, in order")
        for ((name, message) in messages) {
            val fixture = fixtures.getValue(name)
            val decoded = ProtoBuf.decodeFromByteArray<T>(fixture)
            val encoded = ProtoBuf.encodeToByteArray(message).toHexString()
            assertEquals(encoded, ProtoBuf.encodeToByteArray(decoded).toHexString(), name)
            if (exact) {
                assertEquals(fixture.toHexString(), encoded, name)
            }
        }
    }

    /** A caller whose terminal session the platform verified, as the Rust tests build it. */
    fun verifiedCaller() = CallerIdentity(
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
}
