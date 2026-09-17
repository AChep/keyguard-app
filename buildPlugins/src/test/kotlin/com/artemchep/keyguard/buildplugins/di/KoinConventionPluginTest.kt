package com.artemchep.keyguard.buildplugins.di

import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import java.io.File
import org.gradle.api.JavaVersion
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KoinConventionPluginTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `application compiler rejects a missing dependency from another module`() {
        val koinVersion = System.getProperty("keyguard.test.koinVersion")
            ?: error("The outer build must supply the Koin version used by the application")
        writeFile(
            "settings.gradle.kts",
            """
            rootProject.name = "koin-compiler-fixture"
            include(":feature", ":app")
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
        writeFile("gradle.properties", "org.gradle.jvmargs=-Xmx1024m")
        for (project in listOf("feature", "app")) {
            writeFile(
                "$project/build.gradle.kts",
                """
                plugins {
                    id("keyguard.kotlin-multiplatform")
                    id("keyguard.koin")
                }
                kotlin {
                    jvm()
                    sourceSets.commonMain.dependencies {
                        implementation("io.insert-koin:koin-core:$koinVersion")
                    }
                    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
                }
                """,
            )
        }
        file("app/build.gradle.kts").appendText(
            """

            kotlin {
                sourceSets.commonMain.dependencies { implementation(project(":feature")) }
            }
            """.trimIndent() + "\n",
        )
        writeFile(
            "feature/src/commonMain/kotlin/example/Feature.kt",
            """
            package example

            import org.koin.dsl.module
            import org.koin.plugin.module.dsl.single

            class Repository
            class Service(val repository: Repository)

            class FeatureServices {
                val module = module {
                    single<Service>()
                    single<Repository>()
                }
            }

            class FeatureModules {
                val module = module { includes(FeatureServices().module) }
            }
            """,
        )
        writeFile(
            "app/src/commonMain/kotlin/example/Application.kt",
            """
            package example

            import org.koin.dsl.koinApplication
            import org.koin.dsl.module

            // Match real platform roots, which also contribute local definitions.
            class ApplicationModules {
                val module = module { single<String> { "application" } }
            }

            fun application() = koinApplication {
                allowOverride(false)
                modules(FeatureModules().module, ApplicationModules().module)
            }
            """,
        )

        writeFile(
            "app/src/commonTest/kotlin/example/IsolationTest.kt",
            """
            package example

            import kotlin.test.Test
            import kotlin.test.assertNotSame
            import kotlin.test.assertSame

            class IsolationTest {
                @Test fun eachApplicationOwnsItsSingletons() {
                    val first = application()
                    val second = application()
                    try {
                        val firstService = first.koin.get<Service>()
                        val secondService = second.koin.get<Service>()
                        assertSame(firstService, first.koin.get<Service>())
                        assertNotSame(firstService, secondService)
                        assertNotSame(firstService.repository, secondService.repository)
                        first.close()
                        assertSame(secondService, second.koin.get<Service>())
                    } finally {
                        first.close()
                        second.close()
                    }
                }
            }
            """,
        )
        val valid = fixtureGradleRunner(temporaryFolder.root, ":app:jvmTest").build()
        assertEquals(TaskOutcome.SUCCESS, valid.task(":app:compileKotlinJvm")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, valid.task(":app:jvmTest")?.outcome)
        assertTrue(valid.output, !valid.output.contains("KOIN-W003"))

        // Keep a non-empty module while removing its required binding. A clean compiler
        // pass is deliberate: upstream incremental metadata can retain removed bindings.
        val feature = file("feature/src/commonMain/kotlin/example/Feature.kt")
        feature.writeText(
            feature.readText().replace(
                "single<Repository>()",
                "single<String> { \"unrelated\" }",
            ),
        )
        val invalid = fixtureGradleRunner(
            temporaryFolder.root,
            ":feature:clean",
            ":app:clean",
            ":app:compileKotlinJvm",
            "--no-build-cache",
        ).buildAndFail()

        assertEquals(TaskOutcome.FAILED, invalid.task(":app:compileKotlinJvm")?.outcome)
        assertTrue(invalid.output, invalid.output.contains("KOIN-D001"))
        assertTrue(invalid.output, invalid.output.contains("Repository"))
        assertTrue(invalid.output, invalid.output.contains("Service"))
    }

    private fun file(path: String): File = File(temporaryFolder.root, path)

    private fun writeFile(path: String, content: String) {
        file(path).apply {
            parentFile.mkdirs()
            writeText(content.trimIndent() + "\n")
        }
    }
}
