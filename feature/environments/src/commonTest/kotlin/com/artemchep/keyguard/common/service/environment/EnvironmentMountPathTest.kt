package com.artemchep.keyguard.common.service.environment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnvironmentMountPathTest {
    @Test
    fun `every spelling of a path has one form`() {
        for (path in listOf("/proj/.env", "/proj/./.env", "/proj//.env", "//proj/.//./.env", "/./proj/.env")) {
            assertEquals("/proj/.env", normalizeEnvironmentMountPath(path), path)
        }
        assertEquals("/.env", normalizeEnvironmentMountPath("/.env"))
        assertEquals("/proj/my .env", normalizeEnvironmentMountPath("/proj/my .env"))
    }

    @Test
    fun `paths the agent refuses are invalid`() {
        for (path in listOf(
            "",
            ".env",
            "proj/.env",
            "./.env",
            "/",
            "//",
            "/proj/",
            "/proj/.",
            "/proj/..",
            "/proj/../.env",
            "/../proj/.env",
            "/proj/\u0000.env",
        )) {
            assertNull(normalizeEnvironmentMountPath(path), path)
        }
    }
}
