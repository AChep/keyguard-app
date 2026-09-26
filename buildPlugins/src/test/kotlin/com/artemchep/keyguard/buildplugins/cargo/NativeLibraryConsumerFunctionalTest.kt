package com.artemchep.keyguard.buildplugins.cargo

import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeLibraryConsumerFunctionalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun consumersUsePublishedLibrariesWithProducerDependenciesAndConfigurationCache() {
        val root = temporaryFolder.newFolder()
        val modules = listOf("crypto", "io", "zxcvbn")
        writeFile(
            root,
            "settings.gradle.kts",
            """
            rootProject.name = "native-consumer-fixture"
            include(":consumer", ":util:crypto", ":util:io", ":util:zxcvbn")
            """.trimIndent(),
        )
        val assertions = modules.joinToString("\n") { module ->
            val suffix = module.replaceFirstChar(Char::uppercaseChar)
            val cargoTaskName = "cargoBuildNative" + suffix + "Desktop"
            val compileTaskName = "compileNative" + suffix + "Desktop"
            writeFile(
                root,
                "util/$module/build.gradle.kts",
                """
                import com.artemchep.keyguard.buildplugins.cargo.CargoBuildTask
                import com.artemchep.keyguard.buildplugins.cargo.SignAndCopyBinaryTask

                plugins { id("keyguard.cargo-common") }
                keyguardCargo {
                    register("$compileTaskName", "$cargoTaskName")
                    rustTarget.set("fixture-target")
                    cargoBinaryName.set("fixture-$module.bin")
                }
                layout.buildDirectory.set(layout.projectDirectory.dir("relocated-output"))
                tasks.withType<CargoBuildTask>().configureEach { enabled = false }
                tasks.withType<SignAndCopyBinaryTask>().configureEach { enabled = false }
                """.trimIndent(),
            )
            val prebuiltPath = writeFile(
                root,
                "util/$module/relocated-output/cargo-target/fixture-target/release/fixture-$module.bin",
                "native:$module",
            ).invariantSeparatorsPath
            """
            String ${module}Property = System.getProperty("keyguard.native$suffix.libraryPath");
            assertNotNull("Missing native library system property for $module", ${module}Property);
            Path $module = Path.of(${module}Property);
            assertEquals("Unexpected producer artifact for $module", Path.of("$prebuiltPath").toRealPath(), $module.toRealPath());
            assertEquals("Unexpected producer artifact content for $module", "native:$module", Files.readString($module));
            """.trimIndent()
        }
        val junitPath = File(org.junit.Test::class.java.protectionDomain.codeSource.location.toURI()).invariantSeparatorsPath
        val hamcrestPath = File(org.hamcrest.SelfDescribing::class.java.protectionDomain.codeSource.location.toURI()).invariantSeparatorsPath
        writeFile(
            root,
            "consumer/build.gradle.kts",
            """
            plugins {
                java
                id("keyguard.native-crypto-consumer")
                id("keyguard.native-io-consumer")
                id("keyguard.native-zxcvbn-consumer")
            }
            dependencies {
                testImplementation(files("$junitPath", "$hamcrestPath"))
            }
            tasks.test {
                testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
            """.trimIndent(),
        )
        writeFile(
            root,
            "consumer/src/test/java/NativePathsTest.java",
            """
            import org.junit.Test;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import static org.junit.Assert.assertEquals;
            import static org.junit.Assert.assertNotNull;

            public class NativePathsTest {
                @Test public void loadsPathsFromProducerArtifacts() throws Exception {
                    $assertions
                }
            }
            """.trimIndent(),
        )
        val runner = fixtureGradleRunner(root, ":consumer:test", "--configuration-cache")

        val first = runner.build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":consumer:test")?.outcome)
        modules.forEach { module ->
            val suffix = module.replaceFirstChar(Char::uppercaseChar)
            assertEquals(TaskOutcome.SKIPPED, first.task(":util:$module:cargoBuildNative" + suffix + "Desktop")?.outcome)
            assertEquals(TaskOutcome.SKIPPED, first.task(":util:$module:compileNative" + suffix + "Desktop")?.outcome)
        }
        val second = runner.build()
        assertTrue(second.output.contains("Reusing configuration cache."))
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":consumer:test")?.outcome)
    }

    private fun writeFile(root: File, path: String, content: String): File = File(root, path).apply {
        parentFile.mkdirs()
        writeText(content)
    }
}
