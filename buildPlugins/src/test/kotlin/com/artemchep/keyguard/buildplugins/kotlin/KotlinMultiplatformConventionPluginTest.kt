package com.artemchep.keyguard.buildplugins.kotlin

import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import org.gradle.api.JavaVersion
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KotlinMultiplatformConventionPluginTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `base convention respects target selection and runs shared JVM tests`() {
        writeFile(
            "settings.gradle.kts",
            """
            rootProject.name = "shared-jvm-fixture"
            dependencyResolutionManagement {
                repositories { mavenCentral() }
                versionCatalogs {
                    create("libs") {
                        version("jdk", "${JavaVersion.current().majorVersion}")
                    }
                }
            }
            """,
        )
        writeFile("gradle.properties", "kotlin.mpp.applyDefaultHierarchyTemplate=false")
        writeFile(
            "build.gradle.kts",
            """
            import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain
            import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmTest

            plugins { id("keyguard.kotlin-multiplatform") }

            kotlin {
                jvm("desktop")
                sourceSets {
                    // Model the second consumers without requiring an Android SDK for this fixture.
                    create("androidMain")
                    create("androidHostTest")
                    sharedJvmMain(name = "jvmCommonMain")
                    sharedJvmTest()
                }
            }

            tasks.register("verifyPlatformSelection") {
                val targetNames = kotlin.targets.names.toSet()
                doLast {
                    check(targetNames == setOf("metadata", "desktop")) {
                        "The base convention added unrequested platforms: " + targetNames
                    }
                }
            }
            """,
        )
        writeFile(
            "src/jvmCommonMain/kotlin/example/SharedJvm.kt",
            """
            package example

            fun sharedJvmValue(): String = java.nio.file.Path.of("shared-jvm").fileName.toString()
            """,
        )
        writeFile(
            "src/jvmCommonTest/kotlin/example/SharedJvmTest.kt",
            """
            package example

            import kotlin.test.Test
            import kotlin.test.assertEquals

            class SharedJvmTest {
                @Test
                fun readsSharedImplementation() {
                    assertEquals("shared-jvm", sharedJvmValue())
                }
            }
            """,
        )

        val result = fixtureGradleRunner(temporaryFolder.root, "desktopTest", "verifyPlatformSelection").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":desktopTest")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyPlatformSelection")?.outcome)
        val report = File(
            temporaryFolder.root,
            "build/test-results/desktopTest/TEST-example.SharedJvmTest.xml",
        ).readText()
        assertTrue(report.contains("tests=\"1\""))
        assertTrue(report.contains("failures=\"0\""))
    }

    private fun writeFile(path: String, content: String) {
        File(temporaryFolder.root, path).apply {
            parentFile.mkdirs()
            writeText(content.trimIndent() + "\n")
        }
    }
}
