package com.artemchep.keyguard.buildplugins.cargo

import com.artemchep.keyguard.buildplugins.androidssh.AndroidCargoEnvironment
import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RustMultiplatformLibraryFunctionalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cryptoOverridesAndDefaultNativeInputsSurviveConventionConfiguration() {
        val root = temporaryFolder.newFolder()
        val sdk = File(root, "android-sdk")
        val ndk = File(sdk, "ndk/fixture")
        val bin = AndroidCargoEnvironment.hostToolchainBinDir(ndk)
        val windows = detectHostPlatform().isWindows
        val commandSuffix = if (windows) ".cmd" else ""
        val executableSuffix = if (windows) ".exe" else ""
        listOf("aarch64-linux-android", "armv7a-linux-androideabi", "i686-linux-android", "x86_64-linux-android")
            .forEach { target ->
                writeFile(bin, target + "26-clang" + commandSuffix, "fixture")
                writeFile(bin, target + "26-clang++" + commandSuffix, "fixture")
            }
        listOf("llvm-ar", "llvm-ranlib", "llvm-readelf").forEach { tool ->
            writeFile(bin, tool + executableSuffix, "fixture")
        }
        listOf("rust/Cargo.toml", "schema/api.proto", "thirdParty/rust/fork/lib.rs", "cmake/android.toolchain.cmake")
            .forEach { path -> writeFile(root, path, "fixture") }
        writeFile(
            root,
            "settings.gradle.kts",
            """
            rootProject.name = "crypto"
            include(":io", ":zxcvbn")
            dependencyResolutionManagement {
                versionCatalogs {
                    create("libs") {
                        version("androidMinSdk", "26")
                        version("androidNdk", "fixture")
                    }
                }
            }
            """.trimIndent(),
        )
        listOf("io", "zxcvbn").forEach { module ->
            val moduleTaskName = module.replaceFirstChar(Char::uppercaseChar)
            writeFile(root, "$module/rust/Cargo.toml", "fixture")
            writeFile(
                root,
                "$module/build.gradle.kts",
                """
                import com.artemchep.keyguard.buildplugins.androidssh.AndroidCargoEnvironment
                import com.artemchep.keyguard.buildplugins.cargo.CargoBuildTask

                plugins { id("keyguard.rust-multiplatform-library") }

                tasks.register("verifyDefaultNativeModel") {
                    doLast {
                        check(!keyguardRust.androidCmakeToolchainFile.isPresent)
                        listOf("Arm64V8a", "ArmeabiV7a", "X86", "X8664").forEach { suffix ->
                            val cargo = tasks.named<CargoBuildTask>("cargoBuildNative${moduleTaskName}Android" + suffix).get()
                            check(cargo.offline.get() == providers.gradleProperty("expected${moduleTaskName}Offline").get().toBoolean())
                            check(cargo.sourceFiles.files == setOf(file("rust/Cargo.toml"))) {
                                "Unexpected default Android source inputs for $module: " + cargo.sourceFiles.files
                            }
                            val environment = cargo.environmentVariables.get()
                            val cmakeKey = AndroidCargoEnvironment.targetEnvironmentName("CMAKE_TOOLCHAIN_FILE", cargo.rustTarget.get())
                            check(cmakeKey !in environment) { "Unexpected CMake override for $module" }
                            check(environment["KEYGUARD_ANDROID_API_LEVEL"] == "26")
                            check(cargo.linkerExecutable.get().asFile.isFile)
                        }
                    }
                }
                """.trimIndent(),
            )
        }
        writeFile(
            root,
            "build.gradle.kts",
            """
            import com.artemchep.keyguard.buildplugins.androidssh.AndroidCargoEnvironment
            import com.artemchep.keyguard.buildplugins.cargo.CargoBuildTask

            plugins { id("keyguard.rust-multiplatform-library") }
            // Apply overrides after the convention has registered its tasks.
            keyguardRust {
                extraSourceInputs.from(file("schema"), file("thirdParty/rust"))
                androidCmakeToolchainFile.set(layout.projectDirectory.file("cmake/android.toolchain.cmake"))
            }

            tasks.register("verifyNativeModel") {
                doLast {
                    val androidSuffixes = listOf("Arm64V8a", "ArmeabiV7a", "X86", "X8664")
                    val appleSuffixes = listOf("IosArm64", "IosSimulatorArm64", "MacosArm64")
                    val cargoNames = listOf("cargoBuildNativeCryptoDesktop") +
                        androidSuffixes.map { "cargoBuildNativeCryptoAndroid" + it } +
                        appleSuffixes.map { "cargoBuildNativeCrypto" + it }
                    check(cargoNames.size == 8)
                    cargoNames.forEach { name ->
                        val cargo = tasks.named<CargoBuildTask>(name).get()
                        check(cargo.sourceFiles.files.containsAll(listOf(file("schema/api.proto"), file("thirdParty/rust/fork/lib.rs"))))
                        check(cargo.offline.get() == providers.gradleProperty("expectedCryptoOffline").get().toBoolean())
                        check(cargo.cargoArguments.get().contains("--locked"))
                        val packageName = if (name in appleSuffixes.map { "cargoBuildNativeCrypto" + it }) {
                            "keyguard-crypto-c"
                        } else {
                            "keyguard-crypto-jni"
                        }
                        check(cargo.cargoPackage.get() == packageName)
                        if ("Android" in name) {
                            val cmake = file("cmake/android.toolchain.cmake")
                            check(cmake in cargo.sourceFiles.files)
                            val cmakeKey = AndroidCargoEnvironment.targetEnvironmentName("CMAKE_TOOLCHAIN_FILE", cargo.rustTarget.get())
                            check(cargo.environmentVariables.get()[cmakeKey] == cmake.absolutePath)
                            check(cargo.environmentVariables.get()["KEYGUARD_ANDROID_API_LEVEL"] == "26")
                            check(cargo.linkerExecutable.get().asFile.isFile)
                        }
                    }
                    androidSuffixes.forEach { suffix ->
                        val prepare = tasks.named("prepareNativeCryptoAndroid" + suffix).get()
                        val dependencies = prepare.taskDependencies.getDependencies(prepare).map { it.name }
                        check("verifyNativeCryptoAndroid" + suffix + "PageAlignment" in dependencies)
                        check("cargoBuildNativeCryptoAndroid" + suffix in dependencies)
                    }
                    val aggregate = tasks.named("compileNativeCryptoAll").get()
                    check(
                        aggregate.taskDependencies.getDependencies(aggregate).map { it.name }.toSet() ==
                            setOf("compileNativeCryptoAndroidAll", "compileNativeCryptoAppleAll", "compileNativeCryptoDesktop")
                    )
                }
            }
            """.trimIndent(),
        )
        val result = fixtureGradleRunner(
            root,
            "verifyNativeModel",
            ":io:verifyDefaultNativeModel",
            ":zxcvbn:verifyDefaultNativeModel",
            "-Pkeyguard.nativeCargo.cargoOffline=true",
            "-Pkeyguard.nativeCrypto.cargoOffline=false",
            "-Pkeyguard.nativeIo.cargoOffline=false",
            "-PexpectedCryptoOffline=false",
            "-PexpectedIoOffline=false",
            "-PexpectedZxcvbnOffline=true",
            "--no-configuration-cache",
        )
            .withEnvironment(System.getenv() + ("ANDROID_SDK_ROOT" to sdk.absolutePath))
            .build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyNativeModel")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":io:verifyDefaultNativeModel")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":zxcvbn:verifyDefaultNativeModel")?.outcome)
    }

    private fun writeFile(root: File, path: String, content: String): File = File(root, path).apply {
        parentFile.mkdirs()
        writeText(content)
    }
}
