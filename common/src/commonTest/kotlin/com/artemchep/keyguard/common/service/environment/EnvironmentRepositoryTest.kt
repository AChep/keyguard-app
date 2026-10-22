package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.test.environmentPayload
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.cli.agent.error
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.test.createSecret
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class)
class EnvironmentRepositoryTest {
    @Test
    fun `append without a value preserves existing secret and creates an unset variable`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.repository.appendVariables("environment", listOf("TOKEN" to null, "MISSING" to null))
        val definition = fixture.saved.single().environment!!
        assertEquals(listOf(
            EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal(SECRET)),
            EnvironmentVariable("MISSING", EnvironmentValueSource.Unset),
        ), definition.variables)
        assertEquals(DSecret.Type.Environment, fixture.saved.single().type)
    }

    @Test
    fun `unsupported source definitions remain readable as metadata but cannot be edited`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value = listOf(fixture.secret.copy(environment = environmentPayload(
            EnvironmentDefinition(listOf(EnvironmentVariable("TOKEN", EnvironmentValueSource.Unsupported(
                Json.parseToJsonElement("""{"kind":"future","value":"retained"}"""),
            )))),
        )))
        fixture.ready()
        assertFalse(fixture.repository.load("environment").editable)
        val error = assertFailsWith<EnvironmentServiceException> {
            fixture.repository.rename("environment", "renamed")
        }
        assertEquals("invalid_environment", error.code)
        assertTrue(fixture.saved.isEmpty())
    }

    @Test
    fun `hidden collection blocks UI loading and secret mutation`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value = listOf(fixture.secret.copy(collectionIds = setOf("collection")))
        fixture.collections.value = listOf(collection(hidden = true))
        fixture.ready()
        assertFalse(fixture.repository.state.value.environments.single().editable)
        assertEquals("permission_denied", assertFailsWith<EnvironmentServiceException> {
            fixture.repository.load("environment")
        }.code)
        assertEquals("permission_denied", assertFailsWith<EnvironmentServiceException> {
            fixture.repository.save("environment", "account", "name", EnvironmentDefinition())
        }.code)
        assertTrue(fixture.saved.isEmpty())
    }

    @Test
    fun `readonly collection allows reading but blocks changes`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value = listOf(fixture.secret.copy(collectionIds = setOf("collection")))
        fixture.collections.value = listOf(collection(readOnly = true))
        fixture.ready()
        assertFalse(fixture.repository.load("environment").editable)
        assertEquals("permission_denied", assertFailsWith<EnvironmentServiceException> {
            fixture.repository.rename("environment", "changed")
        }.code)
        assertTrue(fixture.saved.isEmpty())
    }

    @Test
    fun `collection matching is account scoped and missing permissions fail closed`() {
        val secret = createSecret("environment", accountId = "account", collectionIds = setOf("collection"))
        assertFalse(environmentPermissions(secret, emptyList()).canRead)
        assertFalse(environmentPermissions(secret, listOf(collection().copy(accountId = "other"))).canRead)
        val withTwo = secret.copy(collectionIds = setOf("collection", "visible"))
        val visible = collection().copy(id = "visible")
        assertTrue(environmentPermissions(withTwo, listOf(collection(hidden = true), visible)).canRead)
        assertFalse(environmentPermissions(withTwo, listOf(collection(readOnly = true), visible)).canWrite)
    }

    @Test
    fun `locking cancels an in flight write before the provider mutation resumes`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        fixture.beforeSave = { entered.complete(Unit); resume.await() }
        val write = async {
            assertFailsWith<EnvironmentServiceException> {
                fixture.repository.save("environment", "account", "changed", EnvironmentDefinition())
            }
        }
        entered.await()
        fixture.lock()
        resume.complete(Unit)
        assertEquals("vault_locked", write.await().code)
        assertTrue(fixture.saved.isEmpty())
    }
}
