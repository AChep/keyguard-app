package com.artemchep.keyguard.buildplugins.testing

import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import org.gradle.api.JavaVersion
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JvmE2eConventionPluginTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `suite stays opt in discovers named sources and runs on every invocation`() {
        writeFile(
            "settings.gradle.kts",
            """
            rootProject.name = "sampleE2eTest"
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
        writeFile(
            "build.gradle.kts",
            """
            plugins { id("keyguard.jvm-e2e") }
            dependencies { testImplementation("org.jetbrains.kotlin:kotlin-test-junit") }
            """,
        )
        writeFile(
            "src/sampleE2eTest/kotlin/example/NamedSuiteTest.kt",
            """
            package example

            import kotlin.test.Test
            import kotlin.test.assertEquals

            class NamedSuiteTest {
                @Test
                fun readsSuiteResource() {
                    val value = javaClass.getResource("/fixture.txt")!!.readText().trim()
                    assertEquals("named suite", value)
                }
            }
            """,
        )
        writeFile("src/sampleE2eTest/resources/fixture.txt", "named suite")
        writeFile(
            "src/test/kotlin/example/OrdinaryTest.kt",
            """
            package example

            import kotlin.test.Test

            class OrdinaryTest {
                @Test
                fun mustStayDisabled() {
                    error("The ordinary test task must stay disabled.")
                }
            }
            """,
        )

        val check = runner("check").build()
        assertNull(check.task(":sampleE2eTest"))
        assertEquals(TaskOutcome.SKIPPED, check.task(":test")?.outcome)

        val first = runner("sampleE2eTest").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":sampleE2eTest")?.outcome)
        val report = File(
            temporaryFolder.root,
            "build/test-results/sampleE2eTest/TEST-example.NamedSuiteTest.xml",
        ).readText()
        assertTrue(report.contains("tests=\"1\""))
        assertTrue(report.contains("failures=\"0\""))

        val second = runner("sampleE2eTest").build()
        assertEquals(TaskOutcome.SUCCESS, second.task(":sampleE2eTest")?.outcome)
    }

    private fun runner(vararg tasks: String): GradleRunner = fixtureGradleRunner(temporaryFolder.root, *tasks)

    private fun writeFile(path: String, content: String) {
        File(temporaryFolder.root, path).apply {
            parentFile.mkdirs()
            writeText(content.trimIndent() + "\n")
        }
    }
}
