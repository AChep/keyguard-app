package com.artemchep.keyguard.buildplugins.android

import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AndroidConventionsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun preservesApplicationOverridesAndSigning() {
        val projectDir = temporaryFolder.newFolder("android-conventions")
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "android-conventions-test"
            include(":phone", ":wear", ":library", ":benchmark", ":client")
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
                versionCatalogs {
                    create("libs") {
                        version("jdk", "21")
                        version("androidCompileSdk", "37")
                        version("androidMinSdk", "26")
                        version("androidTargetSdk", "37")
                        version("androidNdk", "27.0.12077973")
                        library("android-desugarjdklibs", "com.android.tools", "desugar_jdk_libs").version("2.1.5")
                        library("androidx-test-orchestrator", "androidx.test", "orchestrator").version("1.6.1")
                    }
                }
            }
            """.trimIndent(),
        )
        projectDir.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx1536m\n")
        writeModule(projectDir, "phone", productionApplication("phone", minSdk = 26, releaseSigning = true))
        writeModule(projectDir, "wear", productionApplication("wear", minSdk = 30, releaseSigning = false))
        writeSigningProperties(projectDir, "phone", "qa")
        writeSigningProperties(projectDir, "phone", "release")
        writeSigningProperties(projectDir, "wear", "qa")
        writeModule(projectDir, "library", library())
        writeModule(projectDir, "benchmark", benchmark())
        writeModule(projectDir, "client", client())
        val result = fixtureGradleRunner(projectDir, "verifyConventions").build()
        listOf("phone", "wear", "library", "benchmark", "client").forEach { module ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":$module:verifyConventions")?.outcome)
        }
    }

    private fun writeModule(root: File, name: String, script: String) {
        root.resolve(name).apply { mkdirs() }.resolve("build.gradle.kts").writeText(script)
    }

    private fun writeSigningProperties(root: File, module: String, signing: String) {
        root.resolve("$module/keyguard-$signing.properties").writeText(
            """
            key_alias=$module-$signing-alias
            password_store=$module-$signing-key-password
            password_key=$module-$signing-store-password
            """.trimIndent(),
        )
    }

    private fun productionApplication(name: String, minSdk: Int, releaseSigning: Boolean): String {
        val releaseAlias = if (releaseSigning) "\"$name-release-alias\"" else "null"
        val releaseKeyPassword = if (releaseSigning) "\"$name-release-key-password\"" else "null"
        val releaseStorePassword = if (releaseSigning) "\"$name-release-store-password\"" else "null"
        val jvmChecks = jvmAssertions(minSdk)
        return """
        import com.android.build.api.variant.Variant
        import com.artemchep.keyguard.buildplugins.android.configureKeyguardApplication

        plugins {
            id("com.android.application")
            id("keyguard.android-application")
        }
        android {
            configureKeyguardApplication(project)
            namespace = "test.$name"
            defaultConfig {
                applicationId = "test.$name"
                minSdk = $minSdk
                versionCode = 42
                versionName = "1.2.3"
            }
        }
        val applicationVariants = mutableListOf<Variant>()
        androidComponents.onVariants { applicationVariants.add(it) }
        tasks.register("verifyConventions") {
            doLast {
                $jvmChecks
                check(android.defaultConfig.targetSdk == 37)
                check(android.defaultConfig.applicationId == "test.$name")
                check(android.defaultConfig.versionCode == 42)
                check(android.defaultConfig.versionName == "1.2.3")
                check(android.defaultConfig.testInstrumentationRunner == "androidx.test.runner.AndroidJUnitRunner")
                check(android.defaultConfig.testInstrumentationRunnerArguments["clearPackageData"] == "true")
                check(android.testOptions.execution == "androidx_test_orchestrator")
                check(android.defaultConfig.vectorDrawables.useSupportLibrary == true)
                check(android.bundle.language.enableSplit == false)
                check(android.buildFeatures.buildConfig == true)
                check(android.compileOptions.isCoreLibraryDesugaringEnabled)
                check(configurations.getByName("coreLibraryDesugaring").dependencies.single().name == "desugar_jdk_libs")
                check(configurations.getByName("androidTestUtil").dependencies.single().name == "orchestrator")
                check(android.flavorDimensions == listOf("accountManagement"))
                check(android.productFlavors.names == setOf("none", "playStore"))
                check(applicationVariants.size == 4)
                applicationVariants.forEach { variant ->
                    val flavor = variant.productFlavors.single { it.first == "accountManagement" }.second
                    val analytics = checkNotNull(variant.buildConfigFields).get().getValue("ANALYTICS")
                    check(analytics.type == "boolean")
                    check(analytics.value == (flavor == "playStore").toString())
                }
                val debugSigning = android.signingConfigs.getByName("debug")
                check(debugSigning.storeFile == file("keyguard-qa.keystore"))
                check(debugSigning.keyAlias == "$name-qa-alias")
                check(debugSigning.keyPassword == "$name-qa-key-password")
                check(debugSigning.storePassword == "$name-qa-store-password")
                val releaseSigning = android.signingConfigs.getByName("release")
                check(releaseSigning.storeFile == file("keyguard-release.keystore"))
                check(releaseSigning.keyAlias == $releaseAlias)
                check(releaseSigning.keyPassword == $releaseKeyPassword)
                check(releaseSigning.storePassword == $releaseStorePassword)
                val release = android.buildTypes.getByName("release")
                check(release.signingConfig == releaseSigning)
                check(release.isMinifyEnabled && release.isShrinkResources)
                check(release.proguardFiles == listOf(
                    android.getDefaultProguardFile("proguard-android-optimize.txt"),
                    file("../common/proguard-rules.pro"),
                    file("proguard-rules.pro"),
                ))
                check(android.buildTypes.getByName("debug").applicationIdSuffix == ".debug")
                check(android.testBuildType == "debug")
                println("Verified $name")
            }
        }
        """.trimIndent()
    }

    private fun library(): String {
        val jvmChecks = jvmAssertions()
        return """
        import com.artemchep.keyguard.buildplugins.android.accountManagementFlavors
        plugins {
            id("keyguard.android-library")
            id("com.android.library")
        }
        android {
            namespace = "test.library"
            testOptions.targetSdk = 37
            accountManagementFlavors()
        }
        tasks.register("verifyConventions") {
            doLast {
                $jvmChecks
                check(android.testOptions.targetSdk == 37)
                check(android.productFlavors.names == setOf("none", "playStore"))
                check(android.buildFeatures.buildConfig != true)
                check(android.buildTypes.names == setOf("debug", "release"))
                check(!android.compileOptions.isCoreLibraryDesugaringEnabled)
                check(android.signingConfigs.findByName("release") == null)
                println("Verified library")
            }
        }
        """.trimIndent()
    }

    private fun benchmark(): String {
        val jvmChecks = jvmAssertions()
        return """
        import com.artemchep.keyguard.buildplugins.android.accountManagementFlavors
        plugins {
            id("com.android.test")
            id("keyguard.android-test")
        }
        android {
            namespace = "test.benchmark"
            targetProjectPath = ":phone"
            accountManagementFlavors()
        }
        tasks.register("verifyConventions") {
            doLast {
                $jvmChecks
                check(android.defaultConfig.targetSdk == 37)
                check(android.targetProjectPath == ":phone")
                check(android.productFlavors.names == setOf("none", "playStore"))
                check(android.buildFeatures.buildConfig != true)
                check(!android.compileOptions.isCoreLibraryDesugaringEnabled)
                println("Verified benchmark")
            }
        }
        """.trimIndent()
    }

    private fun client(): String {
        val jvmChecks = jvmAssertions(minSdk = 30)
        return """
        import com.artemchep.keyguard.buildplugins.android.enableCoreLibraryDesugaring
        plugins {
            id("com.android.application")
            id("keyguard.android-application")
        }
        check(!android.compileOptions.isCoreLibraryDesugaringEnabled)
        android {
            namespace = "test.client"
            defaultConfig {
                applicationId = "test.client"
                minSdk = 30
                testInstrumentationRunnerArguments["notAnnotation"] = "test.SlowTest"
            }
            enableCoreLibraryDesugaring(project)
        }
        tasks.register("verifyConventions") {
            doLast {
                $jvmChecks
                check(android.defaultConfig.targetSdk == 37)
                check(android.productFlavors.isEmpty())
                check(android.defaultConfig.testInstrumentationRunnerArguments == mapOf("notAnnotation" to "test.SlowTest"))
                check(android.buildFeatures.buildConfig != true)
                check(android.signingConfigs.findByName("release") == null)
                check(!android.buildTypes.getByName("release").isMinifyEnabled)
                check(android.compileOptions.isCoreLibraryDesugaringEnabled)
                check(configurations.getByName("androidTestUtil").dependencies.isEmpty())
                println("Verified client")
            }
        }
        """.trimIndent()
    }

    private fun jvmAssertions(minSdk: Int = 26): String =
        """
        check(android.compileSdk == 37)
        check(android.defaultConfig.minSdk == $minSdk)
        check(android.compileOptions.sourceCompatibility.toString() == "21")
        check(android.compileOptions.targetCompatibility.toString() == "21")
        check(kotlin.compilerOptions.jvmTarget.get().target == "21")
        check(!pluginManager.hasPlugin("org.jetbrains.kotlin.android"))
        """.trimIndent()
}
