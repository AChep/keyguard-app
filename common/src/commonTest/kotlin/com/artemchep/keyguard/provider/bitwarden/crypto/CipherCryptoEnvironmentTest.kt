package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.common.service.patch.ModelDiffUtil
import com.artemchep.keyguard.common.service.serialization.createApplicationJson
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.getMergeRules
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.feature.environments.editableDefinition
import com.artemchep.keyguard.provider.bitwarden.entity.CipherTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.of
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CipherCryptoEnvironmentTest {
    @Test
    fun `environment crosses secure note transport and restores first class type`() {
        val cipher = cipher("secret\n🔑".repeat(600))
        val encrypted = cipher.transform(identityEncrypt, identityEncrypt)
        assertNull(encrypted.environment)
        assertEquals(CipherTypeEntity.SecureNote, CipherTypeEntity.of(encrypted.type))
        val decrypted = encrypted.copy(type = BitwardenCipher.Type.SecureNote)
            .transform(identityDecrypt, identityDecrypt)
        assertEquals(BitwardenCipher.Type.Environment, decrypted.type)
        assertEquals(cipher.environment, decrypted.environment)
        assertEquals(cipher.fields, decrypted.fields)
    }

    @Test
    fun `corrupt transport stays a secure note and round trips intact`() {
        val encrypted = cipher("secret").transform(identityEncrypt, identityEncrypt)
        val bad = encrypted.copy(type = BitwardenCipher.Type.SecureNote, fields = encrypted.fields.dropLast(1))
        val decoded = bad.transform(identityDecrypt, identityDecrypt)
        assertEquals(BitwardenCipher.Type.SecureNote, decoded.type)
        assertNull(decoded.environment)
        assertEquals(bad.fields, decoded.fields)
        assertEquals(bad.fields, decoded.transform(identityEncrypt, identityEncrypt).fields)
    }

    @Test
    fun `a secure note with a field under the environment name stays a secure note`() {
        val note = BitwardenCipher.Field(
            name = "keyguard.environment.note",
            value = "mine",
            type = BitwardenCipher.Field.Type.Text,
        )
        val secureNote = cipher("secret").copy(type = BitwardenCipher.Type.SecureNote, environment = null)
            .copy(fields = listOf(note))
        val decoded = secureNote.transform(identityEncrypt, identityEncrypt)
            .transform(identityDecrypt, identityDecrypt)
        assertEquals(BitwardenCipher.Type.SecureNote, decoded.type)
        assertNull(decoded.environment)
        assertEquals(listOf(note), decoded.fields)
    }

    @Test
    fun `an environment wins over GPG fields, which stay custom fields`() {
        val gpgField = BitwardenCipher.Field(
            name = GpgAgentFields.PUBLIC_KEY_ARMORED,
            value = "public",
            type = BitwardenCipher.Field.Type.Text,
        )
        val cipher = cipher("secret")
        val encrypted = cipher.transform(identityEncrypt, identityEncrypt)
        val decoded = encrypted.copy(type = BitwardenCipher.Type.SecureNote, fields = encrypted.fields + gpgField)
            .transform(identityDecrypt, identityDecrypt)
        assertEquals(BitwardenCipher.Type.Environment, decoded.type)
        assertEquals(cipher.environment, decoded.environment)
        assertNull(decoded.gpgKey)
        assertEquals(listOf(gpgField), decoded.fields)
    }

    @Test
    fun `other cipher types neither write nor read environment fields`() {
        val transport = cipher("secret").transform(identityEncrypt, identityEncrypt).fields
        val login = cipher("secret").copy(type = BitwardenCipher.Type.Login, secureNote = null)
        assertTrue(login.transform(identityEncrypt, identityEncrypt).fields.isEmpty())

        val decoded = login.copy(environment = null, fields = transport)
            .transform(identityEncrypt, identityEncrypt)
            .transform(identityDecrypt, identityDecrypt)
        assertEquals(BitwardenCipher.Type.Login, decoded.type)
        assertNull(decoded.environment)
        assertEquals(transport, decoded.fields)
    }

    @Test
    fun `an environment with nothing stored keeps its type through the transport`() {
        val encrypted = cipher("secret").copy(environment = BitwardenCipher.Environment())
            .transform(identityEncrypt, identityEncrypt)
        val decrypted = encrypted.copy(type = BitwardenCipher.Type.SecureNote)
            .transform(identityDecrypt, identityDecrypt)
        assertEquals(BitwardenCipher.Type.Environment, decrypted.type)
        assertEquals(EnvironmentDefinition(), decrypted.environment?.toDomain().editableDefinition())
        assertTrue(decrypted.fields.isEmpty())
    }

    @Test
    fun `simultaneous edits select one complete remote payload without chunk merging`() {
        val original = cipher("old")
        val local = cipher("local").copy(notes = "local description")
        val remote = cipher("remote")
        val merged = ModelDiffUtil().run {
            BitwardenCipher.getMergeRules().merge(original, local, remote) as BitwardenCipher
        }
        assertEquals(remote.environment, merged.environment)
        assertEquals(local.notes, merged.notes)
        assertTrue(merged.fields.isEmpty())
    }

    @Test
    fun `local payload edit survives unrelated remote name edit and persisted copy`() {
        val original = cipher("old")
        val local = cipher("local")
        val remote = original.copy(name = "Renamed remotely")
        val merged = ModelDiffUtil().run {
            BitwardenCipher.getMergeRules().merge(original, local, remote) as BitwardenCipher
        }
        assertEquals(local.environment, merged.environment)
        assertEquals(remote.name, merged.name)
        assertEquals(merged, Json.decodeFromString<BitwardenCipher>(Json.encodeToString(merged)))
        assertEquals(merged.environment, merged.copy(cipherId = "new-id").environment)
    }

    @Test
    fun `simultaneous edits of different variables are both kept`() {
        val original = cipher("old").copy(environment = environment("HOST" to "db.local", "TOKEN" to "old"))
        val local = original.copy(environment = environment("HOST" to "db.internal", "TOKEN" to "old"))
        val remote = original.copy(environment = environment("HOST" to "db.local", "TOKEN" to "rotated"))
        val merged = ModelDiffUtil().run {
            BitwardenCipher.getMergeRules().merge(original, local, remote) as BitwardenCipher
        }
        assertEquals(environment("HOST" to "db.internal", "TOKEN" to "rotated"), merged.environment)
    }

    @Test
    fun `variables added on both sides to an empty environment are both kept`() {
        val original = cipher("old").copy(environment = BitwardenCipher.Environment())
        val local = original.copy(environment = environment("HOST" to "db.local"))
        val remote = original.copy(environment = environment("TOKEN" to "secret"))
        val merged = ModelDiffUtil().run {
            BitwardenCipher.getMergeRules().merge(original, local, remote) as BitwardenCipher
        }
        // The order only affects display.
        assertEquals(
            setOf(
                EnvironmentVariable("HOST", EnvironmentValueSource.Literal("db.local")),
                EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal("secret")),
            ),
            merged.environment?.document?.let(EnvironmentFormat::decode)?.definition?.variables?.toSet(),
        )
    }

    @Test
    fun `simultaneous edits keep one complete payload when one is not decoded`() {
        val original = cipher("old")
        val local = cipher("local")
        val remote = original.copy(environment = BitwardenCipher.Environment(document = """{"version":2}"""))
        val merged = ModelDiffUtil().run {
            BitwardenCipher.getMergeRules().merge(original, local, remote) as BitwardenCipher
        }
        assertEquals(remote.environment, merged.environment)
    }

    @Test
    fun `absent payloads fall back to the single value rule`() {
        val original = cipher("old").copy(environment = null)
        val local = cipher("local")
        val remote = original.copy(name = "Renamed remotely")
        val merged = ModelDiffUtil().run {
            BitwardenCipher.getMergeRules().merge(original, local, remote) as BitwardenCipher
        }
        assertEquals(local.environment, merged.environment)
    }

    @Test
    fun `simultaneous edits keep one complete document when one is read with loss`() {
        val original = cipher("old").copy(environment = environment("HOST" to "db.local", "TOKEN" to "old"))
        val local = original.copy(environment = environment("HOST" to "db.internal", "TOKEN" to "old"))
        val future = """{"version":1,"variables":[""" +
            """{"name":"HOST","source":{"kind":"literal","value":"db.local"}},""" +
            """{"name":"TOKEN","source":{"kind":"literal","value":"rotated","secret":true}}]}"""
        val remote = original.copy(environment = BitwardenCipher.Environment(document = future))
        val merged = ModelDiffUtil().run {
            BitwardenCipher.getMergeRules().merge(original, local, remote) as BitwardenCipher
        }
        assertEquals(remote.environment, merged.environment)
    }

    @Test
    fun `a change on one side keeps its document byte for byte`() {
        val original = cipher("old")
        // Valid, but not how this version would encode it.
        val reordered = """{"variables":[{"source":{"value":"new","kind":"literal"},"name":"TOKEN"}],"version":1}"""
        val remote = original.copy(environment = BitwardenCipher.Environment(document = reordered))
        val merged = ModelDiffUtil().run {
            BitwardenCipher.getMergeRules().merge(original, original, remote) as BitwardenCipher
        }
        assertEquals(reordered, merged.environment?.document)
    }

    @Test
    fun `stored rows keep the document under fixed keys`() {
        val json = createApplicationJson()
        val cipher = cipher("secret")
        val row = json.encodeToJsonElement(BitwardenCipher.serializer(), cipher).jsonObject
        val document = """{"version":1,"variables":[{"name":"TOKEN","source":{"kind":"literal","value":"secret"}}]}"""
        assertEquals(Json.parseToJsonElement("""{"document":${Json.encodeToString(document)}}"""), row["environment"])
    }

    private fun environment(vararg variables: Pair<String, String>) = BitwardenCipher.Environment(
        document = EnvironmentFormat.encode(EnvironmentDefinition(variables.map { (name, value) ->
            EnvironmentVariable(name, EnvironmentValueSource.Literal(value))
        })),
    )

    private fun cipher(value: String) = BitwardenCipher(
        accountId = "account", cipherId = "environment", revisionDate = Instant.fromEpochSeconds(0),
        service = BitwardenService(), name = "Environment", notes = "description", favorite = false,
        reprompt = BitwardenCipher.RepromptType.None, type = BitwardenCipher.Type.Environment,
        secureNote = BitwardenCipher.SecureNote(),
        environment = environment("TOKEN" to value),
    )
}
