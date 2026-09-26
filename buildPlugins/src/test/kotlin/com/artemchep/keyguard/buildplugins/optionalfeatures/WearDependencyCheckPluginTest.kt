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
        val result = fixtureGradleRunner(root, checkTask, "--configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":$checkTask")?.outcome)
        assertTrue(result.output, result.output.contains("checked 3 production compile/runtime classpaths"))

        val cached = fixtureGradleRunner(root, checkTask, "--configuration-cache").build()
        assertTrue(cached.output, cached.output.contains("Reusing configuration cache"))
        assertEquals(TaskOutcome.SUCCESS, cached.task(":$checkTask")?.outcome)
    }

    @Test
    fun manifestGateAndLifecycleCheckFailWithoutAnApplicationPlugin() {
        val root = fixture("")
        listOf("checkOptionalFeatureManifests", "check").forEach { task ->
            val result = fixtureGradleRunner(root, task, "--configuration-cache").buildAndFail()

            assertEquals(TaskOutcome.FAILED, result.task(":checkOptionalFeatureManifests")?.outcome)
            assertTrue(result.output, result.output.contains("No Wear production merged manifests were checked."))
        }
    }

    @Test
    fun manifestGateFailsWhenAllAndroidVariantsAreDisabled() {
        val root = fixture("")
        root.resolve("build.gradle").appendText(
            """

            apply plugin: 'com.android.application'
            android {
                namespace = 'test.wear'
                compileSdk = 37
            }
            androidComponents.beforeVariants(androidComponents.selector().all()) { variant ->
                variant.enable = false
            }
            """.trimIndent(),
        )

        val result = fixtureGradleRunner(root, "checkOptionalFeatureManifests").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":checkOptionalFeatureManifests")?.outcome)
        assertTrue(result.output, result.output.contains("No Wear production merged manifests were checked."))
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
    fun rejectsAndroidIpcProjectsPulledTransitively() {
        val root = fixture("add('noneDebugRuntimeClasspath', project(':common'))")
        root.resolve("settings.gradle").appendText(
            "\ninclude ':common', ':feature:android-ipc-android', ':feature:android-ipc-presentation'\n",
        )
        root.resolve("common").apply { mkdirs() }.resolve("build.gradle").writeText(
            """
            plugins { id 'java-library' }
            dependencies {
                api project(':feature:android-ipc-android')
                api project(':feature:android-ipc-presentation')
            }
            """.trimIndent(),
        )
        listOf("android-ipc-android", "android-ipc-presentation").forEach { module ->
            root.resolve("feature/$module").apply { mkdirs() }.resolve("build.gradle")
                .writeText("plugins { id 'java-library' }\n")
        }

        val result = fixtureGradleRunner(root, checkTask).buildAndFail()
        assertTrue(
            result.output,
            result.output.contains("noneDebugRuntimeClasspath -> project :feature:android-ipc-android"),
        )
        assertTrue(
            result.output,
            result.output.contains("noneDebugRuntimeClasspath -> project :feature:android-ipc-presentation"),
        )
    }

    @Test
    fun rejectsBothOpenKeychainArtifactsFromTheirExactResolvedGroup() {
        val root = fixture(
            """
            add('noneDebugCompileClasspath', 'com.github.open-keychain.open-keychain:openpgp-api:1.0')
            add('playStoreReleaseRuntimeClasspath', 'fixture:openkeychain-bridge:1.0')
            add('noneDebugRuntimeClasspath', 'fixture:sshauthentication-api:1.0')
            """.trimIndent(),
        )

        val result = fixtureGradleRunner(root, checkTask).buildAndFail()
        assertTrue(
            result.output,
            result.output.contains(
                "noneDebugCompileClasspath -> " +
                    "com.github.open-keychain.open-keychain:openpgp-api:1.0",
            ),
        )
        assertTrue(
            result.output,
            result.output.contains(
                "playStoreReleaseRuntimeClasspath -> " +
                    "com.github.open-keychain.open-keychain:sshauthentication-api:1.0",
            ),
        )
        assertTrue(result.output, !result.output.contains("noneDebugRuntimeClasspath -> fixture:sshauthentication-api"))
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
        publish(root, "com.github.open-keychain.open-keychain:openpgp-api:1.0")
        publish(root, "com.github.open-keychain.open-keychain:sshauthentication-api:1.0")
        publish(
            root,
            "fixture:openkeychain-bridge:1.0",
            "com.github.open-keychain.open-keychain:sshauthentication-api:1.0",
        )
        publish(root, "fixture:sshauthentication-api:1.0")
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
