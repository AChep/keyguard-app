package com.artemchep.keyguard.buildplugins.optionalfeatures

import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarOutputStream

class WearDependencyCheckPluginTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun checkAllowsFileQrDecoderAndChecksProductionVariants() {
        val root = fixture(
            """
            add('noneDebugRuntimeClasspath', 'com.google.zxing:core:1.0')
            add('unitTestRuntimeClasspath', 'androidx.camera:camera-core:1.0')
            """.trimIndent(),
        )
        val result = fixtureGradleRunner(root, "check", "--configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":$checkTask")?.outcome)
        assertTrue(result.output, result.output.contains("checked 3 production compile/runtime classpaths"))

        val cached = fixtureGradleRunner(root, "check", "--configuration-cache").build()
        assertTrue(cached.output, cached.output.contains("Reusing configuration cache"))
        assertEquals(TaskOutcome.SUCCESS, cached.task(":$checkTask")?.outcome)
    }

    @Test
    fun rejectsTransitiveScannerDependenciesAcrossVariants() {
        val root = fixture(
            """
            add('noneDebugCompileClasspath', 'fixture:camera-bridge:1.0')
            add('playStoreReleaseRuntimeClasspath', 'fixture:mlkit-bridge:1.0')
            """.trimIndent(),
        )
        val result = fixtureGradleRunner(root, checkTask).buildAndFail()
        assertTrue(result.output, result.output.contains("noneDebugCompileClasspath -> androidx.camera:camera-core:1.0"))
        assertTrue(result.output, result.output.contains("playStoreReleaseRuntimeClasspath -> com.google.mlkit:common:1.0"))
    }

    @Test
    fun rejectsScannerProjectPulledThroughCommon() {
        val root = fixture("add('noneDebugRuntimeClasspath', project(':common'))")
        root.resolve("settings.gradle").appendText("\ninclude ':common', ':feature:qr-scanner-android'\n")
        root.resolve("common").apply { mkdirs() }.resolve("build.gradle").writeText(
            """
            plugins { id 'java-library' }
            dependencies { api project(':feature:qr-scanner-android') }
            """.trimIndent(),
        )
        root.resolve("feature/qr-scanner-android").apply { mkdirs() }.resolve("build.gradle")
            .writeText("plugins { id 'java-library' }\n")

        val result = fixtureGradleRunner(root, checkTask).buildAndFail()
        assertTrue(result.output, result.output.contains("noneDebugRuntimeClasspath -> project :feature:qr-scanner-android"))
    }

    @Test
    fun unresolvedDependenciesCannotPassTheBoundaryCheck() {
        val root = fixture("add('noneDebugRuntimeClasspath', 'fixture:missing:1.0')")
        val result = fixtureGradleRunner(root, checkTask).buildAndFail()
        assertTrue(result.output, result.output.contains("unresolved fixture:missing:1.0"))
    }

    private fun fixture(dependencies: String): File {
        val root = temporaryFolder.newFolder()
        root.resolve("settings.gradle").writeText("rootProject.name = 'wear-dependencies'\n")
        root.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx1024m\n")
        root.resolve("build.gradle").writeText(
            """
            plugins {
                id 'base'
                id 'keyguard.wear-dependency-check'
            }
            repositories { maven { url = uri('repo') } }
            configurations {
                noneDebugCompileClasspath { canBeConsumed = false }
                noneDebugRuntimeClasspath { canBeConsumed = false }
                playStoreReleaseRuntimeClasspath { canBeConsumed = false }
                unitTestRuntimeClasspath { canBeConsumed = false }
            }
            dependencies {
                $dependencies
            }
            """.trimIndent(),
        )
        publish(root, "com.google.zxing:core:1.0")
        publish(root, "androidx.camera:camera-core:1.0")
        publish(root, "com.google.mlkit:common:1.0")
        publish(root, "fixture:camera-bridge:1.0", "androidx.camera:camera-core:1.0")
        publish(root, "fixture:mlkit-bridge:1.0", "com.google.mlkit:common:1.0")
        return root
    }

    private fun publish(root: File, coordinate: String, dependency: String? = null) {
        val (group, name, version) = coordinate.split(':')
        val directory = root.resolve("repo/${group.replace('.', '/')}/$name/$version").apply { mkdirs() }
        val dependencyXml = dependency?.split(':')?.let { (dependencyGroup, dependencyName, dependencyVersion) ->
            """
            <dependencies><dependency>
                <groupId>$dependencyGroup</groupId>
                <artifactId>$dependencyName</artifactId>
                <version>$dependencyVersion</version>
            </dependency></dependencies>
            """.trimIndent()
        }.orEmpty()
        directory.resolve("$name-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>$group</groupId>
                <artifactId>$name</artifactId>
                <version>$version</version>
                $dependencyXml
            </project>
            """.trimIndent(),
        )
        JarOutputStream(directory.resolve("$name-$version.jar").outputStream()).use { }
    }

    private companion object {
        const val checkTask = "checkOptionalFeatureDependencies"
    }
}
