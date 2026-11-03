package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/** Builds and packages a utility module's JNI library for the current desktop host. */
class RustJniLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val naming = RustModuleNaming(this)
        val desktopTasks = configureRustJniLibrary(naming)
        tasks.register("compile${naming.nativeTaskName}All") {
            group = "build"
            description = "Builds ${naming.nativeTaskName} artifacts for the current desktop host."
            dependsOn(desktopTasks.compile)
        }
        Unit
    }
}

/** Shared desktop build and test wiring for JNI-only and multiplatform Rust modules. */
internal fun Project.configureRustJniLibrary(
    naming: RustModuleNaming,
    testTaskName: String? = "desktopTest",
): CargoTasks {
    pluginManager.apply("keyguard.cargo-common")

    val hostPlatform = detectHostPlatform()
    val nativeTaskName = naming.nativeTaskName
    val desktopCompileTaskName = "compile${nativeTaskName}Desktop"
    val cargoOffline = cargoOfflineProvider(naming.moduleTaskName)
    val cargoExtension = extensions.getByType<CargoCommonExtension>().apply {
        sourceDir.set(naming.rustSourceDirectory)
        cargoPackage.set("${naming.cargoPackagePrefix}-jni")
        cargoArguments.add("--locked")
        cargoBinaryName.set(hostPlatform.dynamicLibraryName("${naming.nativeLibraryPrefix}_jni"))
    }
    val desktopTasks = cargoExtension.register(
        compileTaskName = desktopCompileTaskName,
        cargoTaskName = "cargoBuild${nativeTaskName}Desktop",
    )

    tasks.withType<CargoBuildTask>().configureEach {
        offline.set(cargoOffline)
    }

    val verifyDesktopRustTarget = tasks.register<VerifyRustTargetInstalledTask>(
        "verify${nativeTaskName}DesktopRustTarget",
    ) {
        sourceDir.set(naming.rustSourceDirectory)
        rustTarget.set(hostPlatform.rustTarget)
    }
    desktopTasks.build.configure {
        dependsOn(verifyDesktopRustTarget)
    }
    tasks.register("$desktopCompileTaskName${hostPlatform.name}") {
        group = "build"
        description = "Builds the $nativeTaskName JNI library for the current ${hostPlatform.name} host."
        dependsOn(desktopTasks.compile)
    }
    tasks.named("assemble") {
        dependsOn(desktopTasks.compile)
    }

    configureNativeLibraryTests(producerPath = path, testTaskName = testTaskName)
    // Fail on JNI misuse in the module's own tests. Benchmarks run without the extra checks.
    tasks.withType<Test>().named { it == "desktopTest" }.configureEach {
        jvmArgs("-Xcheck:jni")
    }
    return desktopTasks
}
