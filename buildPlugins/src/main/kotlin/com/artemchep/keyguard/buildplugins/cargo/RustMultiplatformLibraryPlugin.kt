package com.artemchep.keyguard.buildplugins.cargo

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.artemchep.keyguard.buildplugins.androidssh.AndroidCargoEnvironment
import com.artemchep.keyguard.buildplugins.libs
import com.artemchep.keyguard.buildplugins.version
import com.artemchep.keyguard.buildplugins.versionInt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * Builds the conventional Rust libraries that back a Kotlin Multiplatform utility module.
 *
 * A project named `io`, for example, is expected to provide the Cargo packages
 * `keyguard-io-jni` and `keyguard-io-c`. The resulting libraries are named
 * `keyguard_io_jni` and `keyguard_io_c`.
 */
open class RustMultiplatformLibraryPlugin : Plugin<Project> {
    /**
     * Whether Android and iOS get Rust builds. Plugin variants decide this at apply time:
     * the convention targets already exist, so the Apple cinterops are created immediately.
     */
    protected open val mobileTargets: Boolean = true

    override fun apply(target: Project) = with(target) {
        val extension = extensions.create<RustMultiplatformLibraryExtension>("keyguardRust", project)
        val naming = RustModuleNaming(this)
        val nativeTaskName = naming.nativeTaskName
        val cargoPackagePrefix = naming.cargoPackagePrefix
        val nativeLibraryPrefix = naming.nativeLibraryPrefix
        val rustSourceDirectory = naming.rustSourceDirectory
        // Mobile JVM host tests also load the desktop library in multiplatform modules.
        val desktopTasks = configureRustJniLibrary(
            naming = naming,
            testTaskName = if (mobileTargets) null else "desktopTest",
        )

        val compileAndroidAll = if (mobileTargets) {
            val androidPrepareTasks = registerAndroidLibraries(
                nativeTaskName = nativeTaskName,
                cargoPackage = "$cargoPackagePrefix-jni",
                nativeLibraryName = "${nativeLibraryPrefix}_jni",
                rustSourceDirectory = rustSourceDirectory,
                targets = androidNativeTargets(),
                extension = extension,
            )
            configureAndroidPackaging(
                nativeTaskName = nativeTaskName,
                prepareTasks = androidPrepareTasks,
            )
            tasks.register("compile${nativeTaskName}AndroidAll") {
                group = "build"
                description =
                    "Builds and verifies $nativeTaskName JNI libraries for every supported Android ABI."
                dependsOn(androidPrepareTasks)
            }
        } else {
            null
        }

        val appleTargets = appleNativeTargets()
            .filter { target -> mobileTargets || target.isMacos }
        val compileAppleAll = configureRustAppleLibraries(
            naming = naming,
            targets = appleTargets,
        )
        tasks.register("compile${nativeTaskName}All") {
            group = "build"
            description = "Builds $nativeTaskName artifacts for every supported platform."
            dependsOn(listOfNotNull(compileAndroidAll, compileAppleAll, desktopTasks.compile))
        }
        Unit
    }

    private fun Project.registerAndroidLibraries(
        nativeTaskName: String,
        cargoPackage: String,
        nativeLibraryName: String,
        rustSourceDirectory: org.gradle.api.file.Directory,
        targets: List<AndroidNativeTarget>,
        extension: RustMultiplatformLibraryExtension,
    ): List<TaskProvider<PrepareNativeLibraryTask>> {
        val androidMinSdk = libs.versionInt("androidMinSdk")
        val androidNdk = libs.version("androidNdk")

        return targets.map { target ->
            val suffix = target.androidAbi.toTaskSuffix()
            val cargoTargetDirectory = layout.buildDirectory
                .dir(
                    "native-${name.replace('_', '-')}-cargo-target/" +
                        "android/${target.rustTarget}",
                )
            val cargoOutputBinary = cargoTargetDirectory.map { directory ->
                directory.file(
                    "${target.rustTarget}/release/lib$nativeLibraryName.so",
                )
            }
            val verifyRustTarget = tasks.register<VerifyRustTargetInstalledTask>(
                "verify$nativeTaskName" + "Android${suffix}RustTarget",
            ) {
                sourceDir.set(rustSourceDirectory)
                rustTarget.set(target.rustTarget)
            }
            val androidBuildTools = providers.provider {
                AndroidCargoEnvironment.resolveTargetBuildTools(
                    rootDir = rootDir,
                    localPropertiesFile = null,
                    rustTarget = target.rustTarget,
                    androidApiLevel = androidMinSdk,
                    ndkVersion = androidNdk,
                )
            }
            val ndkDirectoryPath = androidBuildTools.map { tools ->
                tools.toolchain.ndkDir.absolutePath
            }
            val linkerExecutable = layout.file(
                androidBuildTools.map { tools -> tools.cCompiler },
            )
            val cargoBuild = tasks.register<CargoBuildTask>(
                "cargoBuild$nativeTaskName" + "Android$suffix",
            ) {
                dependsOn(verifyRustTarget)
                sourceDir.set(rustSourceDirectory)
                this.cargoTargetDir.set(cargoTargetDirectory)
                rustTarget.set(target.rustTarget)
                this.cargoPackage.set(cargoPackage)
                cargoArguments.add("--locked")
                environmentVariables.put(
                    AndroidCargoEnvironment.rustFlagsEnvironmentName(target.rustTarget),
                    "-C link-arg=-Wl,-z,max-page-size=16384 " +
                        "-C link-arg=-Wl,-z,common-page-size=16384",
                )
                environmentVariables.put("ANDROID_NDK_ROOT", ndkDirectoryPath)
                environmentVariables.put("ANDROID_NDK", ndkDirectoryPath)
                environmentVariables.put(
                    AndroidCargoEnvironment.targetEnvironmentName("CC", target.rustTarget),
                    androidBuildTools.map { tools -> tools.cCompiler.absolutePath },
                )
                environmentVariables.put(
                    AndroidCargoEnvironment.targetEnvironmentName("CXX", target.rustTarget),
                    androidBuildTools.map { tools -> tools.cxxCompiler.absolutePath },
                )
                environmentVariables.put(
                    AndroidCargoEnvironment.targetEnvironmentName("AR", target.rustTarget),
                    androidBuildTools.map { tools -> tools.archiver.absolutePath },
                )
                environmentVariables.put(
                    AndroidCargoEnvironment.targetEnvironmentName("RANLIB", target.rustTarget),
                    androidBuildTools.map { tools -> tools.ranlib.absolutePath },
                )
                environmentVariables.putAll(
                    extension.androidCmakeToolchainFile.map { toolchainFile ->
                        mapOf(
                            AndroidCargoEnvironment.targetEnvironmentName("CMAKE_TOOLCHAIN_FILE", target.rustTarget) to
                                toolchainFile.asFile.absolutePath,
                        )
                    }.orElse(emptyMap()),
                )
                environmentVariables.put("KEYGUARD_ANDROID_ABI", target.androidAbi)
                environmentVariables.put(
                    "KEYGUARD_ANDROID_API_LEVEL",
                    androidMinSdk.toString(),
                )
                outputBinary.set(cargoOutputBinary)
                configureAndroidLinker(linkerExecutable)
            }
            val verifyAlignment = tasks.register<VerifyElfPageAlignmentTask>(
                "verify$nativeTaskName" + "Android${suffix}PageAlignment",
            ) {
                dependsOn(cargoBuild)
                readElfExecutable.set(
                    layout.file(
                        providers.provider {
                            AndroidCargoEnvironment.resolveReadElfExecutable(
                                rootDir = rootDir,
                                localPropertiesFile = null,
                                ndkVersion = androidNdk,
                            )
                        },
                    ),
                )
                binary.set(cargoBuild.flatMap { task -> task.outputBinary })
            }
            tasks.register<PrepareNativeLibraryTask>(
                "prepare$nativeTaskName" + "Android$suffix",
            ) {
                dependsOn(verifyAlignment)
                sourceBinary.set(cargoBuild.flatMap { task -> task.outputBinary })
                relativeDirectory.set(target.androidAbi)
                packagedFileName.set("lib$nativeLibraryName.so")
                outputDirectory.set(
                    layout.buildDirectory.dir(
                        "generated/${nativeTaskName.replaceFirstChar(Char::lowercaseChar)}/" +
                            "jniLibs/${target.androidAbi}",
                    ),
                )
            }
        }
    }

    private fun Project.configureAndroidPackaging(
        nativeTaskName: String,
        prepareTasks: List<TaskProvider<PrepareNativeLibraryTask>>,
    ) {
        pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
            val androidComponents =
                extensions.getByType<KotlinMultiplatformAndroidComponentsExtension>()
            androidComponents.onVariants(androidComponents.selector().all()) { variant ->
                val jniLibs = requireNotNull(variant.sources.jniLibs) {
                    "$nativeTaskName Android variants must expose a jniLibs source set"
                }
                prepareTasks.forEach { prepareTask ->
                    jniLibs.addGeneratedSourceDirectory(prepareTask) { task -> task.outputDirectory }
                }
            }
        }
    }

    private data class AndroidNativeTarget(
        val rustTarget: String,
        val androidAbi: String,
    )

    private fun androidNativeTargets(): List<AndroidNativeTarget> = listOf(
        AndroidNativeTarget("aarch64-linux-android", "arm64-v8a"),
        AndroidNativeTarget("armv7-linux-androideabi", "armeabi-v7a"),
        AndroidNativeTarget("i686-linux-android", "x86"),
        AndroidNativeTarget("x86_64-linux-android", "x86_64"),
    )
}
