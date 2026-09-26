package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.tasks.Input
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class CargoBuildTaskTest {
    @Test
    fun `offline defaults to false and is a task input`() {
        val task = createTask().apply {
            rustTarget.set("x86_64-unknown-linux-gnu")
        }

        assertFalse(task.offline.get())
        assertFalse(task.cargoCommandLine().contains("--offline"))
        assertNotNull(
            CargoBuildTask::class.java
                .getMethod("getOffline")
                .getAnnotation(Input::class.java),
        )
    }

    @Test
    fun `offline flag precedes stable caller arguments when enabled`() {
        val task = createTask().apply {
            rustTarget.set("aarch64-apple-ios")
            cargoPackage.set("keyguard-crypto-c")
            cargoArguments.add("--locked")
            offline.set(true)
        }

        assertEquals(
            listOf(
                "cargo",
                "build",
                "--release",
                "--target",
                "aarch64-apple-ios",
                "--package",
                "keyguard-crypto-c",
                "--offline",
                "--locked",
            ),
            task.cargoCommandLine(),
        )
    }

    @Test
    fun `offline helper builds cannot change the prefetched lockfile`() {
        val task = createTask().apply {
            rustTarget.set("aarch64-apple-darwin")
            offline.set(true)
        }

        assertEquals(1, task.cargoCommandLine().count { it == "--locked" })
        task.cargoArguments.add("--locked")
        assertEquals(1, task.cargoCommandLine().count { it == "--locked" })
    }

    private fun createTask(): CargoBuildTask {
        val project = ProjectBuilder.builder().build()
        return project.tasks.register("cargoBuild", CargoBuildTask::class.java).get()
    }
}
