package com.artemchep.keyguard.buildplugins.nativecrypto

import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarOutputStream

class CryptoDependencyPolicyPluginTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rootChecksEveryOwnerAndAllowsBouncyCastleTestOracles() {
        val result = runner(fixture()).build()

        assertNotNull(result.task(":$checkTask"))
        owners.forEach { owner ->
            assertEquals(TaskOutcome.SUCCESS, result.task("$owner:$checkTask")?.outcome)
            assertTrue(result.output.contains("2 compile/runtime classpaths in $owner."))
        }
    }

    @Test
    fun rejectsProductionBouncyCastleAndTestSshArtifactsIncludingTransitiveDependencies() {
        val violations = mapOf(
            ":common" to ("productionRuntimeClasspath" to bouncyCastle),
            ":androidApp" to ("productionRuntimeClasspath" to bridge),
            ":wearApp" to ("oracleTestRuntimeClasspath" to "com.hierynomus:sshj:1.0"),
            ":desktopApp" to ("oracleTestRuntimeClasspath" to "fixture:asn-one:1.0"),
        )
        val result = runner(fixture(violations = violations), "--continue").buildAndFail()

        violations.forEach { (owner, dependency) ->
            assertEquals(TaskOutcome.FAILED, result.task("$owner:$checkTask")?.outcome)
            val (configuration, coordinate) = dependency
            val forbiddenComponent = if (coordinate == bridge) bouncyCastle else coordinate
            assertTrue(
                "Missing violation for $owner:$configuration",
                result.output.contains("$owner:$configuration -> $forbiddenComponent"),
            )
        }
        assertEquals(TaskOutcome.SUCCESS, result.task(":util:foundation:$checkTask")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":util:kdbx:$checkTask")?.outcome)
    }

    @Test
    fun rootFailsWhenAnOwnerDoesNotApplyItsCheckPlugin() {
        val result = runner(fixture(missingOwner = ":wearApp")).buildAndFail()

        // Gradle can report the task name and owning project separately.
        assertTrue(result.output, result.output.contains(checkTask))
        assertTrue(result.output, result.output.contains(":wearApp"))
        assertTrue(result.output, result.output.contains("not found"))
    }

    private fun fixture(
        violations: Map<String, Pair<String, String>> = emptyMap(),
        missingOwner: String? = null,
    ): File {
        val root = temporaryFolder.newFolder()
        root.resolve("settings.gradle").writeText(
            """
            rootProject.name = 'crypto-policy-test'
            include ':util:foundation', ':util:kdbx', ':common', ':androidApp', ':wearApp', ':desktopApp'
            dependencyResolutionManagement {
                repositories { maven { url = uri('repo') } }
            }
            """.trimIndent(),
        )
        root.resolve("build.gradle").writeText("plugins { id 'keyguard.crypto-dependency-policy' }\n")
        root.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx1024m\n")
        publish(root, bouncyCastle)
        publish(root, bridge, transitiveDependency = bouncyCastle)
        publish(root, "com.hierynomus:sshj:1.0")
        publish(root, "fixture:asn-one:1.0")

        owners.forEach { owner ->
            val plugin = if (owner == missingOwner) "" else "plugins { id 'keyguard.crypto-dependency-check' }"
            val violation = violations[owner]?.let { (configuration, coordinate) ->
                "add('$configuration', '$coordinate')"
            }.orEmpty()
            root.resolve(owner.removePrefix(":").replace(':', '/')).apply { mkdirs() }
                .resolve("build.gradle").writeText(
                    """
                    $plugin
                    configurations {
                        productionRuntimeClasspath {
                            canBeResolved = true
                            canBeConsumed = false
                        }
                        oracleTestRuntimeClasspath {
                            canBeResolved = true
                            canBeConsumed = false
                        }
                    }
                    dependencies {
                        add('oracleTestRuntimeClasspath', '$bridge')
                        $violation
                    }
                    """.trimIndent(),
                )
        }
        return root
    }

    private fun publish(root: File, coordinate: String, transitiveDependency: String? = null) {
        val (group, artifact, version) = coordinate.split(':')
        val directory = root.resolve("repo/" + group.replace('.', '/') + "/$artifact/$version")
            .apply { mkdirs() }
        val dependencies = transitiveDependency?.split(':')?.let { (dependencyGroup, dependencyArtifact, dependencyVersion) ->
            """
            <dependencies><dependency>
                <groupId>$dependencyGroup</groupId>
                <artifactId>$dependencyArtifact</artifactId>
                <version>$dependencyVersion</version>
            </dependency></dependencies>
            """.trimIndent()
        }.orEmpty()
        directory.resolve("$artifact-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>$group</groupId>
                <artifactId>$artifact</artifactId>
                <version>$version</version>
                $dependencies
            </project>
            """.trimIndent(),
        )
        JarOutputStream(directory.resolve("$artifact-$version.jar").outputStream()).use { }
    }

    private fun runner(root: File, vararg arguments: String): GradleRunner =
        fixtureGradleRunner(root, ":$checkTask", *arguments)

    private companion object {
        const val checkTask = "checkBouncyCastleProductionDependencies"
        const val bouncyCastle = "org.bouncycastle:bcprov-jdk18on:1.0"
        const val bridge = "fixture:crypto-bridge:1.0"
        val owners = listOf(
            ":util:foundation",
            ":util:kdbx",
            ":common",
            ":androidApp",
            ":wearApp",
            ":desktopApp",
        )
    }
}
