package com.artemchep.keyguard.buildplugins.version

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VersionInfoTest {
    @Test
    fun `parses a positive version code`() {
        assertEquals(123, parseVersionCode("123"))
        assertEquals(123, parseVersionCode("123 "))
    }

    @Test
    fun `rejects a version code that is not a positive integer`() {
        listOf("", " ", "null", "abc", "1.5", "0", "-5", "9999999999").forEach { value ->
            val e = assertThrows(GradleException::class.java) {
                parseVersionCode(value)
            }
            assertEquals(
                "Gradle property 'versionCode' must be a positive integer, but was '$value'.",
                e.message,
            )
        }
    }
}
