package com.artemchep.keyguard.buildplugins.architecture

import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarOutputStream

class ComposeFreePluginTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun checkLifecycleTaskAllowsPureDependencies() {
        val root = fixture()
        publish(root, pureLibrary)
        pureBuildFile(root).appendText(
            """

            dependencies {
                add('pureCompileClasspath', '$pureLibrary')
                add('pureRuntimeClasspath', '$pureLibrary')
                add('commonMainResolvableDependenciesMetadata', '$pureLibrary')
                add('iosSimulatorArm64CompileKlibraries', '$pureLibrary')
            }
            """.trimIndent(),
        )

        val result = runner(root, ":pure:check").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":pure:$checkTask")?.outcome)
        assertTrue(result.output.contains("4 compile/runtime/metadata classpaths in :pure."))
    }

    @Test
    fun rejectsComposeArtifactsAndLegacyOrApplicationProjectsTransitively() {
        val root = fixture(includeBoundaryProjects = true)
        publish(root, composeRuntime)
        publish(root, composeBridge, transitiveDependency = composeRuntime)
        publish(root, composeUi)
        publish(root, composeResources)
        pureBuildFile(root).appendText(
            """

            dependencies {
                add('pureCompileClasspath', '$composeBridge')
                add('pureRuntimeClasspath', '$composeUi')
                add('commonMainResolvableDependenciesMetadata', project(':bridge'))
                add('pureCompileClasspath', project(':androidApp'))
                add('iosSimulatorArm64CompileKlibraries', '$composeResources')
            }
            """.trimIndent(),
        )

        val result = runner(root).buildAndFail()

        assertTrue(
            result.output,
            result.output.contains("$composeBridge -> $composeRuntime (Compose artifact is forbidden)"),
        )
        assertTrue(result.output, result.output.contains("$composeUi (Compose artifact is forbidden)"))
        assertTrue(
            result.output,
            result.output.contains("$composeResources (Compose artifact is forbidden)"),
        )
        assertTrue(result.output, result.output.contains("project :bridge -> project :common"))
        assertTrue(result.output, result.output.contains("project :androidApp"))
        assertTrue(result.output, result.output.contains("legacy/application project dependency is forbidden"))
    }

    @Test
    fun unresolvedDependenciesFailTheStrictBoundaryCheck() {
        val root = fixture()
        pureBuildFile(root).appendText(
            """

            dependencies {
                add('pureCompileClasspath', 'missing:library:1.0')
            }
            """.trimIndent(),
        )

        val result = runner(root).buildAndFail()

        assertTrue(result.output, result.output.contains("missing:library:1.0"))
        assertTrue(result.output, result.output.contains("dependency could not be resolved"))
    }

    @Test
    fun failsWhenNoDependencyClasspathIsSelected() {
        val root = fixture(withClasspaths = false)

        val result = runner(root).buildAndFail()

        assertTrue(
            result.output,
            result.output.contains("No compile/runtime/metadata classpaths were checked in :pure."),
        )
    }

    @Test
    fun classifiesOnlyComposeGroupsAndRelevantClasspaths() {
        assertTrue(isComposeArtifactGroup("androidx.compose.runtime"))
        assertTrue(isComposeArtifactGroup("org.jetbrains.compose.components"))
        assertFalse(isComposeArtifactGroup("androidx.collection"))
        assertFalse(isComposeArtifactGroup("androidx.lifecycle"))
        assertTrue(isForbiddenProjectPath(":common"))
        assertTrue(isForbiddenProjectPath(":integration:sampleApp"))
        assertFalse(isForbiddenProjectPath(":util:foundation"))

        assertTrue(isComposeFreeClasspath("desktopCompileClasspath"))
        assertTrue(isComposeFreeClasspath("jvmRuntimeClasspath"))
        assertTrue(isComposeFreeClasspath("iosSimulatorArm64CompileKlibraries"))
        assertTrue(isComposeFreeClasspath("commonMainResolvableDependenciesMetadata"))
        assertFalse(isComposeFreeClasspath("kotlinCompilerPluginClasspathMain"))
        assertFalse(isComposeFreeClasspath("detekt"))
    }

    @Test
    fun rejectsTheKotlinComposeCompilerPlugin() {
        val root = fixture()
        installComposePluginStub(root)
        pureBuildFile(root).writeText(
            """
            plugins {
                id 'keyguard.compose-free'
                id 'org.jetbrains.kotlin.plugin.compose'
                id 'base'
            }
            """.trimIndent(),
        )

        val result = runner(root).buildAndFail()

        assertNotNull(result.output, result.task(":pure:$checkTask"))
        assertTrue(
            result.output,
            result.output.contains(
                "applies forbidden Compose plugin 'org.jetbrains.kotlin.plugin.compose'",
            ),
        )
    }

    private fun fixture(
        includeBoundaryProjects: Boolean = false,
        withClasspaths: Boolean = true,
    ): File {
        val root = temporaryFolder.newFolder()
        val includedProjects = if (includeBoundaryProjects) {
            ", ':bridge', ':common', ':androidApp'"
        } else {
            ""
        }
        root.resolve("settings.gradle").writeText(
            """
            rootProject.name = 'compose-free-test'
            include ':pure'$includedProjects
            dependencyResolutionManagement {
                repositories { maven { url = uri('repo') } }
            }
            """.trimIndent(),
        )
        root.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx1024m\n")
        root.resolve("pure").mkdirs()
        val classpaths = if (withClasspaths) {
            """
            configurations {
                pureCompileClasspath {
                    canBeResolved = true
                    canBeConsumed = false
                }
                pureRuntimeClasspath {
                    canBeResolved = true
                    canBeConsumed = false
                }
                commonMainResolvableDependenciesMetadata {
                    canBeResolved = true
                    canBeConsumed = false
                }
                iosSimulatorArm64CompileKlibraries {
                    canBeResolved = true
                    canBeConsumed = false
                }
            }
            """.trimIndent()
        } else {
            ""
        }
        pureBuildFile(root).writeText(
            """
            plugins {
                id 'base'
                id 'keyguard.compose-free'
            }
            $classpaths
            """.trimIndent(),
        )
        if (includeBoundaryProjects) {
            root.resolve("common").mkdirs()
            root.resolve("common/build.gradle").writeText("plugins { id 'java-library' }\n")
            root.resolve("androidApp").mkdirs()
            root.resolve("androidApp/build.gradle").writeText("plugins { id 'java-library' }\n")
            root.resolve("bridge").mkdirs()
            root.resolve("bridge/build.gradle").writeText(
                """
                plugins { id 'java-library' }
                dependencies { api project(':common') }
                """.trimIndent(),
            )
        }
        return root
    }

    private fun installComposePluginStub(root: File) {
        root.resolve("buildSrc/src/main/groovy").mkdirs()
        root.resolve("buildSrc/build.gradle").writeText(
            """
            plugins { id 'groovy-gradle-plugin' }
            gradlePlugin {
                plugins {
                    composeStub {
                        id = 'org.jetbrains.kotlin.plugin.compose'
                        implementationClass = 'ComposeStubPlugin'
                    }
                }
            }
            """.trimIndent(),
        )
        root.resolve("buildSrc/src/main/groovy/ComposeStubPlugin.groovy").writeText(
            """
            import org.gradle.api.Plugin
            import org.gradle.api.Project

            class ComposeStubPlugin implements Plugin<Project> {
                void apply(Project project) {}
            }
            """.trimIndent(),
        )
    }

    private fun pureBuildFile(root: File): File = root.resolve("pure/build.gradle")

    private fun publish(root: File, coordinate: String, transitiveDependency: String? = null) {
        val (group, artifact, version) = coordinate.split(':')
        val directory = root.resolve("repo/" + group.replace('.', '/') + "/$artifact/$version")
            .apply { mkdirs() }
        val dependencies = transitiveDependency
            ?.split(':')
            ?.let { (dependencyGroup, dependencyArtifact, dependencyVersion) ->
                """
                <dependencies><dependency>
                    <groupId>$dependencyGroup</groupId>
                    <artifactId>$dependencyArtifact</artifactId>
                    <version>$dependencyVersion</version>
                </dependency></dependencies>
                """.trimIndent()
            }
            .orEmpty()
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

    private fun runner(root: File, vararg arguments: String): GradleRunner {
        val requestedArguments = if (arguments.isEmpty()) {
            arrayOf(":pure:$checkTask")
        } else {
            arguments
        }
        return fixtureGradleRunner(root, *requestedArguments)
    }

    private companion object {
        const val checkTask = "checkComposeFree"
        const val pureLibrary = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.0"
        const val composeBridge = "fixture:compose-bridge:1.0"
        const val composeRuntime = "org.jetbrains.compose.runtime:runtime:1.0"
        const val composeUi = "androidx.compose.ui:ui:1.0"
        const val composeResources = "org.jetbrains.compose.components:components-resources:1.0"
    }
}
