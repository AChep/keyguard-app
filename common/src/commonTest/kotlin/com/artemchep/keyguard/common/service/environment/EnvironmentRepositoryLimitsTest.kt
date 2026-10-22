package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.test.environmentPayload
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentLimits
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnvironmentRepositoryLimitsTest {
    @Test
    fun `limits prevent appending and delivery but allow repairing stored definitions`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        val definition = EnvironmentDefinition(List(EnvironmentLimits.MAX_VARIABLES) {
            EnvironmentVariable("V$it", EnvironmentValueSource.Literal(""))
        })
        fixture.ciphers.value = listOf(fixture.secret.copy(environment = environmentPayload(definition)))
        fixture.ready()
        val error = assertFailsWith<EnvironmentServiceException> {
            fixture.repository.appendVariables("environment", listOf("NEW" to ""))
        }
        assertEquals("invalid_environment", error.code)
        assertTrue(fixture.saved.isEmpty())
        fixture.repository.appendVariables("environment", listOf("V0" to "updated"))
        assertEquals(EnvironmentLimits.MAX_VARIABLES, fixture.saved.single().environment!!.variables.size)

        val oversized = definition.copy(
            variables = definition.variables + EnvironmentVariable("NEW", EnvironmentValueSource.Unset),
        )
        fixture.ciphers.value = listOf(fixture.secret.copy(environment = environmentPayload(oversized)))
        assertTrue(fixture.repository.load("environment").editable)
        assertEquals("invalid_environment", assertFailsWith<EnvironmentServiceException> {
            fixture.repository.resolveForDelivery("environment")
        }.code)
    }
}
