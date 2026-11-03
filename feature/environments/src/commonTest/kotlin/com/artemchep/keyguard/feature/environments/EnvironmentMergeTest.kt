package com.artemchep.keyguard.feature.environments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnvironmentMergeTest {
    @Test
    fun `edits of different variables are both kept`() {
        val base = definition("DB_HOST" to "db.local", "DB_PASSWORD" to "old")
        val local = definition("DB_HOST" to "db.internal", "DB_PASSWORD" to "old")
        val remote = definition("DB_HOST" to "db.local", "DB_PASSWORD" to "rotated")
        assertEquals(
            definition("DB_HOST" to "db.internal", "DB_PASSWORD" to "rotated"),
            EnvironmentMerge.merge(base, local, remote),
        )
    }

    @Test
    fun `additions follow remote order then local order`() {
        val base = definition("A" to "a")
        val local = definition("LOCAL" to "l", "A" to "a")
        val remote = definition("A" to "a", "REMOTE" to "r")
        assertEquals(
            definition("A" to "a", "REMOTE" to "r", "LOCAL" to "l"),
            EnvironmentMerge.merge(base, local, remote),
        )
    }

    @Test
    fun `deletion of an unchanged variable is kept`() {
        val base = definition("A" to "a", "B" to "b")
        val local = definition("A" to "a")
        val remote = definition("A" to "a2", "B" to "b")
        assertEquals(definition("A" to "a2"), EnvironmentMerge.merge(base, local, remote))
    }

    @Test
    fun `edit wins over deletion on either side`() {
        val base = definition("A" to "a", "B" to "b")
        val local = definition("A" to "a2")
        val remote = definition("B" to "b2")
        assertEquals(
            definition("B" to "b2", "A" to "a2"),
            EnvironmentMerge.merge(base, local, remote),
        )
    }

    @Test
    fun `same change on both sides is kept once`() {
        val base = definition("A" to "a")
        val local = definition("A" to "a2", "B" to "b")
        val remote = definition("A" to "a2", "B" to "b")
        assertEquals(local, EnvironmentMerge.merge(base, local, remote))
    }

    @Test
    fun `different values for one variable are a conflict`() {
        val base = definition("KEY_ID" to "id1", "SECRET" to "s1")
        val local = definition("KEY_ID" to "id2", "SECRET" to "s2")
        val remote = definition("KEY_ID" to "id1", "SECRET" to "s1-fixed")
        assertNull(EnvironmentMerge.merge(base, local, remote))
    }

    @Test
    fun `different additions of one name are a conflict`() {
        val base = definition()
        val local = definition("A" to "local")
        val remote = definition("A" to "remote")
        assertNull(EnvironmentMerge.merge(base, local, remote))
    }

    @Test
    fun `source kinds are compared as whole values`() {
        val base = definition("A" to "a")
        val local = EnvironmentDefinition(listOf(EnvironmentVariable("A", EnvironmentValueSource.Unset)))
        val remote = definition("A" to "a")
        assertEquals(local, EnvironmentMerge.merge(base, local, remote))
    }

    @Test
    fun `repeated names are not merged`() {
        val base = definition("A" to "a")
        val local = definition("A" to "a", "A" to "b")
        val remote = definition("A" to "a")
        assertNull(EnvironmentMerge.merge(base, local, remote))
    }

    @Test
    fun `result over the payload limit is not merged`() {
        val half = "a".repeat(EnvironmentFormat.MAX_PAYLOAD_BYTES / 2)
        val base = definition()
        val local = definition("LOCAL" to half)
        val remote = definition("REMOTE" to half)
        assertNull(EnvironmentMerge.merge(base, local, remote))
    }

    @Test
    fun `independent additions cannot exceed the variable limit`() {
        val base = definition(*Array(EnvironmentLimits.MAX_VARIABLES - 1) { "V$it" to "" })
        val local = base.copy(variables = base.variables + EnvironmentVariable("LOCAL", EnvironmentValueSource.Unset))
        val remote = base.copy(variables = base.variables + EnvironmentVariable("REMOTE", EnvironmentValueSource.Unset))
        assertNull(EnvironmentMerge.merge(base, local, remote))
    }

    private fun definition(vararg variables: Pair<String, String>) = EnvironmentDefinition(
        variables.map { (name, value) ->
            EnvironmentVariable(name, EnvironmentValueSource.Literal(value))
        },
    )
}
