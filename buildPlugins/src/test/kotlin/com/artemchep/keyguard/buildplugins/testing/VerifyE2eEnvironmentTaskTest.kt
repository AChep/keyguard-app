package com.artemchep.keyguard.buildplugins.testing

import org.gradle.api.tasks.Input
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyE2eEnvironmentTaskTest {
    @Test
    fun `the relay suite needs only Cargo by default`() {
        val checks = relayChecks(turn = false)

        assertEquals(listOf(listOf("cargo", "--version")), checks.map { it.command })
        assertTrue(checks.single().requirement.contains("server/relay"))
    }

    @Test
    fun `the TURN suite also needs a running Docker daemon`() {
        val checks = relayChecks(turn = true)

        assertEquals(
            listOf(
                listOf("cargo", "--version"),
                listOf("docker", "version", "--format", "{{.Server.Version}}"),
            ),
            checks.map { it.command },
        )
        val docker = checks.last().requirement
        assertTrue(docker.contains("-P$P2P_E2E_TURN_PROPERTY=true"))
        assertTrue(docker.contains("DOCKER_HOST"))
    }

    @Test
    fun `the Bitwarden suite keeps its Docker requirement`() {
        val check = dockerCheck(requirement = "Bitwarden E2E tests require a running Docker daemon")

        assertEquals(listOf("docker", "version", "--format", "{{.Server.Version}}"), check.command)
        assertEquals(
            "Bitwarden E2E tests require a running Docker daemon " +
                "('docker' on PATH, or DOCKER_HOST pointing at the daemon)",
            check.requirement,
        )
    }

    @Test
    fun `TURN is off unless requested and is a task input`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("verifyE2eEnvironment", VerifyE2eEnvironmentTask::class.java).get()

        assertFalse(task.relayTurn.get())
        assertNotNull(
            VerifyE2eEnvironmentTask::class.java
                .getMethod("getRelayTurn")
                .getAnnotation(Input::class.java),
        )
    }
}
