package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * The names a Rust backed utility module derives from its Gradle project name.
 *
 * A project named `io`, for example, provides the Cargo packages `keyguard-io-jni` and
 * `keyguard-io-c`, producing the `keyguard_io_jni` and `keyguard_io_c` libraries, and
 * registers its tasks under the `NativeIo` name.
 */
internal class RustModuleNaming(project: Project) {
    val moduleName: String = project.name.replace('-', '_')
    val moduleTaskName: String = project.name.toTaskSuffix()
    val nativeTaskName: String = "Native$moduleTaskName"
    val cargoPackagePrefix: String = "keyguard-${project.name.replace('_', '-')}"
    val nativeLibraryPrefix: String = "keyguard_$moduleName"
    val rustSourceDirectory: Directory = project.layout.projectDirectory.dir("rust")
}

/**
 * A Kotlin/Native Apple target and the Rust target triple that backs it.
 */
internal data class AppleNativeTarget(
    val kotlinTarget: String,
    val rustTarget: String,
)

internal fun appleNativeTargets(): List<AppleNativeTarget> = listOf(
    AppleNativeTarget("iosArm64", "aarch64-apple-ios"),
    AppleNativeTarget("iosSimulatorArm64", "aarch64-apple-ios-sim"),
    AppleNativeTarget("macosArm64", "aarch64-apple-darwin"),
)

/**
 * Resolves whether Cargo should run in offline mode for the given module.
 *
 * The lookup order is module specific first, then the shared native-Cargo property, then the
 * legacy native-crypto property, and finally `false`.
 */
internal fun Project.cargoOfflineProvider(moduleTaskName: String): Provider<Boolean> =
    providers.gradleProperty("keyguard.native$moduleTaskName.cargoOffline")
        .orElse(providers.gradleProperty("keyguard.nativeCargo.cargoOffline"))
        // Compatibility with release jobs that predate the reusable
        // native-Cargo convention. Remove after those jobs migrate.
        .orElse(providers.gradleProperty("keyguard.nativeCrypto.cargoOffline"))
        .map(String::toBooleanStrict)
        .orElse(false)

/**
 * Registers the Cargo build tasks that produce the static libraries for the Apple targets.
 */
internal fun Project.registerAppleLibraries(
    nativeTaskName: String,
    cargoPackage: String,
    nativeLibraryName: String,
    rustSourceDirectory: Directory,
    targets: List<AppleNativeTarget>,
): Map<String, TaskProvider<CargoBuildTask>> = targets.associate { target ->
    val suffix = target.kotlinTarget.replaceFirstChar(Char::uppercaseChar)
    val cargoTargetDirectory = layout.buildDirectory
        .dir(
            "native-${name.replace('_', '-')}-cargo-target/" +
                "apple/${target.rustTarget}",
        )
    val cargoOutputBinary = cargoTargetDirectory.map { directory ->
        directory.file(
            "${target.rustTarget}/release/lib$nativeLibraryName.a",
        )
    }
    val verifyRustTarget = tasks.register<VerifyRustTargetInstalledTask>(
        "verify$nativeTaskName${suffix}RustTarget",
    ) {
        sourceDir.set(rustSourceDirectory)
        rustTarget.set(target.rustTarget)
    }
    val cargoBuild = tasks.register<CargoBuildTask>("cargoBuild$nativeTaskName$suffix") {
        dependsOn(verifyRustTarget)
        sourceDir.set(rustSourceDirectory)
        sourceFiles.from(
            fileTree(rustSourceDirectory) {
                exclude("target/**", "**/target/**")
            },
        )
        this.cargoTargetDir.set(cargoTargetDirectory)
        rustTarget.set(target.rustTarget)
        this.cargoPackage.set(cargoPackage)
        cargoArguments.add("--locked")
        outputBinary.set(cargoOutputBinary)
    }
    target.kotlinTarget to cargoBuild
}

/**
 * Wires the cinterop of every Apple target to the matching Cargo build task.
 */
internal fun Project.configureAppleInterop(
    moduleName: String,
    moduleTaskName: String,
    nativeTaskName: String,
    rustSourceDirectory: Directory,
    targets: List<AppleNativeTarget>,
    cargoTasks: Map<String, TaskProvider<CargoBuildTask>>,
) {
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
        kotlin.targets.withType<KotlinNativeTarget>().configureEach {
            val nativeTarget = this
            val targetSpec = targets.firstOrNull { target ->
                target.kotlinTarget == nativeTarget.name
            } ?: return@configureEach
            val cargoBuild = checkNotNull(cargoTasks[nativeTarget.name])
            val staticLibraryDirectory = layout.buildDirectory
                .dir(
                    "native-${moduleName.replace('_', '-')}-cargo-target/" +
                        "apple/${targetSpec.rustTarget}/${targetSpec.rustTarget}/release",
                )
                .get()
                .asFile
                .absolutePath

            compilations.getByName("main").cinterops.create("native$moduleTaskName") {
                definitionFile.set(
                    layout.projectDirectory.file(
                        "src/nativeInterop/cinterop/native$moduleTaskName.def",
                    ),
                )
                packageName("com.artemchep.keyguard.util.$moduleName.ffi")
                includeDirs(
                    rustSourceDirectory.dir(
                        "crates/keyguard-${moduleName.replace('_', '-')}-c/include",
                    ),
                )
                extraOpts("-libraryPath", staticLibraryDirectory)
            }
            kotlin.sourceSets.getByName("${nativeTarget.name}Main")
                .kotlin
                .srcDir("src/appleInteropMain/kotlin")

            val interopTaskName =
                "cinterop$nativeTaskName${nativeTarget.name.replaceFirstChar(Char::uppercaseChar)}"
            tasks.matching { task -> task.name == interopTaskName }.configureEach {
                dependsOn(cargoBuild)
                inputs.file(cargoBuild.flatMap { task -> task.outputBinary })
            }
        }
    }
}

/**
 * Registers the aggregate `compile<Native><Target>` tasks and the `compile<Native>AppleAll` task
 * that builds every supported Apple target.
 */
internal fun Project.registerAppleAggregateTasks(
    nativeTaskName: String,
    cargoTasks: Map<String, TaskProvider<CargoBuildTask>>,
): TaskProvider<*> {
    val compileAppleAll = tasks.register("compile${nativeTaskName}AppleAll") {
        group = "build"
        description = "Builds $nativeTaskName static libraries for all supported Apple targets."
        dependsOn(cargoTasks.values)
    }
    cargoTasks.forEach { (targetName, cargoTask) ->
        tasks.register("compile$nativeTaskName${targetName.replaceFirstChar(Char::uppercaseChar)}") {
            group = "build"
            description = "Builds the $nativeTaskName static library for $targetName."
            dependsOn(cargoTask)
        }
    }
    return compileAppleAll
}

internal fun String.toTaskSuffix(): String = split('-', '_')
    .filter(String::isNotBlank)
    .joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }
