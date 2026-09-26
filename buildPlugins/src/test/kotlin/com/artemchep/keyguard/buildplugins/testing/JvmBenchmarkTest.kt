package com.artemchep.keyguard.buildplugins.testing

import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JvmBenchmarkTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun `benchmark stays separate from ordinary tests and reruns with fresh reports`() {
        write("settings.gradle.kts", "rootProject.name = \"benchmark-fixture\"")
        write(
            "build.gradle.kts",
            """
            import com.artemchep.keyguard.buildplugins.testing.benchmarkReport
            import com.artemchep.keyguard.buildplugins.testing.flightRecorder
            import com.artemchep.keyguard.buildplugins.testing.forwardSystemProperties
            import com.artemchep.keyguard.buildplugins.testing.registerJvmBenchmark

            plugins {
                java
                id("keyguard.jvm-e2e") apply false
            }
            repositories { mavenCentral() }
            dependencies { testImplementation("junit:junit:4.13.2") }

            tasks.register("desktopTestClasses") { dependsOn(tasks.testClasses) }
            tasks.register<Test>("desktopTest") {
                testClassesDirs = sourceSets.test.get().output.classesDirs
                classpath = sourceSets.test.get().runtimeClasspath
                filter { excludeTestsMatching("example.BenchmarkTest") }
            }
            registerJvmBenchmark("fixtureBenchmark", "Runs the fixture benchmark", "example.BenchmarkTest") {
                forwardSystemProperties(listOf("keyguard.fixture.iterations"))
                benchmarkReport("keyguard.fixture.report", layout.buildDirectory.file("reports/benchmark.csv"), clearExisting = true)
                flightRecorder(layout.buildDirectory.file("reports/benchmark.jfr"), clearExisting = true)
            }
        """,
        )
        write(
            "src/test/java/example/RegularTest.java",
            """
            package example;
            import org.junit.Test;
            public class RegularTest {
                @Test public void ordinaryTest() { }
            }
        """,
        )
        write(
            "src/test/java/example/BenchmarkTest.java",
            """
            package example;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import org.junit.Test;
            import static org.junit.Assert.*;
            public class BenchmarkTest {
                @Test public void benchmark() throws Exception {
                    assertEquals("en", System.getProperty("user.language"));
                    assertEquals("US", System.getProperty("user.country"));
                    assertEquals("42", System.getProperty("keyguard.fixture.iterations"));
                    Path report = Path.of(System.getProperty("keyguard.fixture.report"));
                    assertFalse("The previous report must be deleted before each run", Files.exists(report));
                    Files.writeString(report, "iterations,42");
                }
            }
        """,
        )
        write("build/reports/benchmark.csv", "stale report")

        val first = runner("desktopTest", "fixtureBenchmark").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":desktopTest")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":fixtureBenchmark")?.outcome)
        assertTrue(file("build/test-results/desktopTest/TEST-example.RegularTest.xml").isFile)
        assertFalse(file("build/test-results/desktopTest/TEST-example.BenchmarkTest.xml").exists())
        assertTrue(file("build/test-results/fixtureBenchmark/TEST-example.BenchmarkTest.xml").isFile)
        assertFalse(file("build/test-results/fixtureBenchmark/TEST-example.RegularTest.xml").exists())
        assertTrue(file("build/reports/benchmark.jfr").length() > 0L)

        val second = runner("fixtureBenchmark").build()
        assertNull("A benchmark must not run the ordinary test task", second.task(":desktopTest"))
        assertEquals(TaskOutcome.SUCCESS, second.task(":fixtureBenchmark")?.outcome)
        assertEquals("iterations,42", file("build/reports/benchmark.csv").readText())
    }

    private fun runner(vararg tasks: String): GradleRunner =
        fixtureGradleRunner(
            directory.root,
            *tasks,
            "-Dkeyguard.fixture.iterations=42",
        )

    private fun file(path: String): File = File(directory.root, path)

    private fun write(path: String, content: String) {
        file(path).apply {
            parentFile.mkdirs()
            writeText(content.trimIndent() + "\n")
        }
    }
}
