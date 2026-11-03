package com.artemchep.keyguard.core.store.bitwarden

import com.artemchep.keyguard.feature.auth.companion.CompanionBitwardenEnvironment
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerHeader
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BitwardenTokenEnvironmentTest {
    private val json = Json

    @Test
    fun `predefined region round trips without mutual tls`() {
        val env = BitwardenToken.Environment.of(
            ServerEnv(
                region = ServerEnv.Region.EU,
            ),
        )

        val decoded = roundTrip(env)

        assertNull(decoded.mutualTls)
        assertEquals(ServerEnv.Region.EU, decoded.back().region)
        assertNull(decoded.back().mutualTls)
    }

    @Test
    fun `custom environment round trips notifications url and mutual tls`() {
        val env = BitwardenToken.Environment.of(
            ServerEnv(
                baseUrl = "https://vault.example.com",
                notificationsUrl = "https://notify.example.com",
                headers = listOf(
                    ServerHeader(
                        key = "X-Test",
                        value = "1",
                    ),
                ),
                mutualTls = ServerEnv.MutualTls.ImportedPkcs12(
                    pkcs12Base64 = "Zm9v",
                ),
            ),
        )

        val decoded = roundTrip(env)
        val back = decoded.back()

        assertEquals(env.notificationsUrl, decoded.notificationsUrl)
        assertEquals(env.mutualTls, decoded.mutualTls)
        assertEquals("https://notify.example.com", back.notificationsUrl)
        assertEquals(env.mutualTls, back.mutualTls)
    }

    @Test
    fun `region fallback preserves explicitly configured mutual tls`() {
        val env = BitwardenToken.Environment(
            region = BitwardenToken.Environment.Region.US,
            mutualTls = ServerEnv.MutualTls.ImportedPkcs12(
                pkcs12Base64 = "Zm9v",
            ),
        )

        val decoded = roundTrip(env)
        val back = decoded.back()

        assertEquals(env.mutualTls, decoded.mutualTls)
        assertEquals(env.mutualTls, back.mutualTls)
        assertEquals(ServerEnv.Region.US, back.region)
    }

    @Test
    fun `legacy mutual tls json ignores stored file metadata`() {
        val compatJson = Json {
            ignoreUnknownKeys = true
        }
        val current = BitwardenToken.Environment(
            baseUrl = "https://vault.example.com",
            mutualTls = ServerEnv.MutualTls.ImportedPkcs12(
                pkcs12Base64 = "Zm9v",
            ),
        )
        val legacyJson = compatJson.encodeToJsonElement(
            BitwardenToken.Environment.serializer(),
            current,
        )
        val legacyMutualTls = buildJsonObject {
            for ((key, value) in legacyJson.jsonObject.getValue("mutualTls").jsonObject) {
                put(key, value)
            }
            put("fileName", JsonPrimitive("client.p12"))
            put("fileSize", JsonPrimitive(123))
        }
        val legacyObject = buildJsonObject {
            for ((key, value) in legacyJson.jsonObject) {
                put(key, value)
            }
            put("mutualTls", legacyMutualTls)
        }
        val decoded = compatJson.decodeFromString(
            BitwardenToken.Environment.serializer(),
            legacyObject.toString(),
        )

        val mutualTls = decoded.mutualTls as? ServerEnv.MutualTls.ImportedPkcs12
        assertTrue(mutualTls != null)
        assertEquals("Zm9v", mutualTls.pkcs12Base64)
    }

    @Test
    fun `legacy accounts decode without a certificate`() {
        val decoded = json.decodeFromString<BitwardenToken.Environment>("{}")
        assertNull(decoded.mutualTls)
        assertEquals("", decoded.notificationsUrl)
    }

    @Test
    fun `companion transfer preserves certificate and notification endpoint`() {
        val env = ServerEnv(
            baseUrl = "https://vault.example.test",
            notificationsUrl = "https://notify.example.test",
            mutualTls = ServerEnv.MutualTls.ImportedPkcs12("test-bundle"),
        )
        val encoded = json.encodeToString(
            CompanionBitwardenEnvironment.serializer(),
            CompanionBitwardenEnvironment.of(env),
        )
        val restored = json.decodeFromString(CompanionBitwardenEnvironment.serializer(), encoded).toServerEnv()
        assertEquals(env, restored)
    }

    private fun roundTrip(
        env: BitwardenToken.Environment,
    ): BitwardenToken.Environment =
        json.decodeFromString(
            BitwardenToken.Environment.serializer(),
            json.encodeToString(
                BitwardenToken.Environment.serializer(),
                env,
            ),
        )
}
