package com.artemchep.keyguard.buildplugins.cargo

import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RustJniLibraryFunctionalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun jniLibraryPreservesTaskAndArtifactContractsWithoutAppleOrAndroidBuilds() {
        val root = temporaryFolder.newFolder()
        val module = File(root, "windows-passkeys")
        File(module, "rust").mkdirs()
        File(module, "rust/Cargo.toml").writeText("fixture")
        File(root, "settings.gradle.kts").writeText("include(\":windows-passkeys\")")
        File(module, "build.gradle.kts").writeText(
            """
            import com.artemchep.keyguard.buildplugins.cargo.CargoBuildTask
            import com.artemchep.keyguard.buildplugins.cargo.CargoCommonPlugin
            import com.artemchep.keyguard.buildplugins.cargo.NativeLibraryPathArgumentProvider
            import com.artemchep.keyguard.buildplugins.cargo.SignAndCopyBinaryTask
            import com.artemchep.keyguard.buildplugins.cargo.VerifyRustTargetInstalledTask
            import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform
            import com.artemchep.keyguard.buildplugins.cargo.dynamicLibraryName
            import org.gradle.api.tasks.testing.Test

            plugins { id("keyguard.rust-jni-library") }

            tasks.register<Test>("desktopTest")
            tasks.register<Test>("otherTest")
            tasks.register("verifyNativeModel") {
                doLast {
                    val host = detectHostPlatform()
                    val binaryName = host.dynamicLibraryName("keyguard_windows_passkeys_jni")
                    val cargo = tasks.named<CargoBuildTask>("cargoBuildNativeWindowsPasskeysDesktop").get()
                    val compile = tasks.named<SignAndCopyBinaryTask>("compileNativeWindowsPasskeysDesktop").get()
                    val verifyTarget = tasks.named<VerifyRustTargetInstalledTask>("verifyNativeWindowsPasskeysDesktopRustTarget").get()
                    val binary = file("build/cargo-target/" + host.rustTarget + "/release/" + binaryName)
                    check(tasks.withType<CargoBuildTask>().names == setOf(cargo.name))
                    check(cargo.sourceDir.get().asFile == file("rust"))
                    check(cargo.rustTarget.get() == host.rustTarget)
                    check(cargo.cargoPackage.get() == "keyguard-windows-passkeys-jni")
                    check(cargo.cargoArguments.get() == listOf("--locked"))
                    check(cargo.offline.get() == providers.gradleProperty("expectedOffline").get().toBoolean())
                    check(cargo.outputBinary.get().asFile == binary)
                    check(verifyTarget.sourceDir.get().asFile == file("rust"))
                    check(verifyTarget.rustTarget.get() == host.rustTarget)
                    check(verifyTarget in cargo.taskDependencies.getDependencies(cargo))
                    check(cargo in compile.taskDependencies.getDependencies(compile))
                    listOf("assemble", "compileNativeWindowsPasskeysAll", "compileNativeWindowsPasskeysDesktop" + host.name)
                        .forEach { name ->
                            val task = tasks.named(name).get()
                            check(task.taskDependencies.getDependencies(task) == setOf(compile))
                        }

                    val nativeArtifact = configurations.getByName(CargoCommonPlugin.NATIVE_DESKTOP_LIBRARY_ELEMENTS_CONFIGURATION_NAME)
                        .artifacts.single()
                    check(nativeArtifact.file == binary)
                    check(compile in nativeArtifact.buildDependencies.getDependencies(null))
                    val bundledArtifact = configurations.getByName(CargoCommonPlugin.BUNDLED_APP_RESOURCES_ELEMENTS_CONFIGURATION_NAME)
                        .artifacts.single()
                    check(bundledArtifact.file == file("build/bundled-app-resources"))
                    check(compile in bundledArtifact.buildDependencies.getDependencies(null))

                    val desktopTest = tasks.named<Test>("desktopTest").get()
                    val arguments = desktopTest.jvmArgumentProviders.filterIsInstance<NativeLibraryPathArgumentProvider>().single()
                    check(arguments.systemPropertyName == "keyguard.nativeWindowsPasskeys.libraryPath")
                    check(arguments.library.singleFile == binary)
                    check(compile in desktopTest.taskDependencies.getDependencies(desktopTest))
                    check(desktopTest.jvmArgs.orEmpty().count { it == "-Xcheck:jni" } == 1)
                    check(tasks.named<Test>("otherTest").get().jvmArgumentProviders.none { it is NativeLibraryPathArgumentProvider })
                }
            }
            """.trimIndent(),
        )

        val offlineCases = listOf(
            listOf("-PexpectedOffline=false"),
            listOf("-Pkeyguard.nativeCargo.cargoOffline=true", "-Pkeyguard.nativeCrypto.cargoOffline=false", "-PexpectedOffline=true"),
            listOf("-Pkeyguard.nativeWindowsPasskeys.cargoOffline=false", "-Pkeyguard.nativeCargo.cargoOffline=true", "-PexpectedOffline=false"),
            listOf("-Pkeyguard.nativeCrypto.cargoOffline=true", "-PexpectedOffline=true"),
        )
        offlineCases.forEach { properties ->
            val result = fixtureGradleRunner(
                root,
                ":windows-passkeys:verifyNativeModel",
                "--no-configuration-cache",
                *properties.toTypedArray(),
            ).build()
            assertEquals(TaskOutcome.SUCCESS, result.task(":windows-passkeys:verifyNativeModel")?.outcome)
        }
    }
}
