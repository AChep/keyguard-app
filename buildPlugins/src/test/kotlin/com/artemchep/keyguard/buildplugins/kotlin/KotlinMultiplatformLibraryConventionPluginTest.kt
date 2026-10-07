package com.artemchep.keyguard.buildplugins.kotlin

import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KotlinMultiplatformLibraryConventionPluginTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun configuresMinorCompileSdkAndSharedTargets() {
        val projectDir = temporaryFolder.newFolder("kmp-library-conventions")
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "kmp-library-conventions-test"
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
                versionCatalogs {
                    create("libs") {
                        version("jdk", "21")
                        version("androidCompileSdk", "37")
                        version("androidCompileSdkMinor", "1")
                        version("androidMinSdk", "26")
                    }
                }
            }
            """.trimIndent(),
        )
        projectDir.resolve("gradle.properties").writeText(
            "org.gradle.jvmargs=-Xmx1536m\nkotlin.mpp.applyDefaultHierarchyTemplate=false\n",
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import com.android.build.api.dsl.CompileSdkVersion

            plugins { id("keyguard.kotlin-multiplatform-library") }

            var configuredCompileSdk: CompileSdkVersion? = null
            kotlin {
                android {
                    namespace = "test.library"
                    compileSdk { configuredCompileSdk = version }
                }
            }
            val androidTarget = kotlin.android
            val targetNames = kotlin.targets.names.toSet()
            tasks.register("verifyConventions") {
                doLast {
                    check(configuredCompileSdk?.apiLevel == 37)
                    check(configuredCompileSdk?.minorApiLevel == 1)
                    check(androidTarget.minSdk == 26)
                    check(androidTarget.compilations.names.contains("hostTest"))
                    check(targetNames == setOf("metadata", "android", "desktop", "iosArm64", "iosSimulatorArm64", "macosArm64"))
                }
            }
            """.trimIndent(),
        )

        val result = fixtureGradleRunner(projectDir, "verifyConventions").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyConventions")?.outcome)
    }

    @Test
    fun derivesNamespaceFromProjectPath() {
        val projectDir = temporaryFolder.newFolder("kmp-library-namespace")
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "kmp-library-namespace-test"
            include(":util:sample-lib", ":util:custom")
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
                versionCatalogs {
                    create("libs") {
                        version("jdk", "21")
                        version("androidCompileSdk", "37")
                        version("androidCompileSdkMinor", "1")
                        version("androidMinSdk", "26")
                    }
                }
            }
            """.trimIndent(),
        )
        projectDir.resolve("gradle.properties").writeText(
            "org.gradle.jvmargs=-Xmx1536m\nkotlin.mpp.applyDefaultHierarchyTemplate=false\n",
        )
        projectDir.resolve("util/sample-lib").mkdirs()
        projectDir.resolve("util/sample-lib/build.gradle.kts").writeText(
            """
            plugins { id("keyguard.kotlin-multiplatform-library") }

            val namespace = kotlin.android.namespace
            tasks.register("verifyNamespace") {
                doLast { check(namespace == "com.artemchep.keyguard.util.sample.lib") { namespace.toString() } }
            }
            """.trimIndent(),
        )
        projectDir.resolve("util/custom").mkdirs()
        projectDir.resolve("util/custom/build.gradle.kts").writeText(
            """
            plugins { id("keyguard.kotlin-multiplatform-library") }

            kotlin { android { namespace = "test.custom" } }
            val namespace = kotlin.android.namespace
            tasks.register("verifyNamespace") {
                doLast { check(namespace == "test.custom") { namespace.toString() } }
            }
            """.trimIndent(),
        )

        val result = fixtureGradleRunner(projectDir, "verifyNamespace").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":util:sample-lib:verifyNamespace")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":util:custom:verifyNamespace")?.outcome)
    }
}
