package com.artemchep.keyguard.buildplugins.cargo

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.artemchep.keyguard.buildplugins.androidssh.AndroidCargoEnvironment
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * Builds the conventional Rust libraries that back a Kotlin Multiplatform utility module.
 *
 * A project named `io`, for example, is expected to provide the Cargo packages
 * `keyguard-io-jni` and `keyguard-io-c`. The resulting libraries are named
 * `keyguard_io_jni` and `keyguard_io_c`.
 */
class RustMultiplatformLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("keyguard.cargo-common")

        val naming = RustModuleNaming(this)
        val moduleName = naming.moduleName
        val moduleTaskName = naming.moduleTaskName
        val nativeTaskName = naming.nativeTaskName
        val cargoPackagePrefix = naming.cargoPackagePrefix
        val nativeLibraryPrefix = naming.nativeLibraryPrefix
        val rustSourceDirectory = naming.rustSourceDirectory
        val hostPlatform = detectHostPlatform()
        val desktopLibraryFileName = hostPlatform.dynamicLibraryName("${nativeLibraryPrefix}_jni")
        val desktopCargoTaskName = "cargoBuild${nativeTaskName}Desktop"
        val desktopCompileTaskName = "compile${nativeTaskName}Desktop"
        val cargoOffline = cargoOfflineProvider(moduleTaskName)

        extensions.configure<CargoCommonExtension> {
            sourceDir.set(rustSourceDirectory)
            rustTarget.set(hostPlatform.desktopLibRustTarget)
            cargoPackage.set("$cargoPackagePrefix-jni")
            cargoArguments.add("--locked")
            cargoBinaryName.set(desktopLibraryFileName)
            packagedBinaryName.set(desktopLibraryFileName)
            composeResourceDir.set(hostPlatform.composeResourceDir)
            cargoTaskName.set(desktopCargoTaskName)
            compileTaskName.set(desktopCompileTaskName)
            platformMacOs.set(hostPlatform.isMacOs)
            platformWindows.set(hostPlatform.isWindows)
            markExecutable.set(true)
        }

        tasks.withType<CargoBuildTask>().configureEach {
            offline.set(cargoOffline)
        }

        val verifyDesktopRustTarget = tasks.register<VerifyRustTargetInstalledTask>(
            "verify${nativeTaskName}DesktopRustTarget",
        ) {
            sourceDir.set(rustSourceDirectory)
            rustTarget.set(hostPlatform.desktopLibRustTarget)
        }
        tasks.matching { task -> task.name == desktopCargoTaskName }.configureEach {
            dependsOn(verifyDesktopRustTarget)
        }

        val androidTargets = androidNativeTargets()
        val androidPrepareTasks = registerAndroidLibraries(
            nativeTaskName = nativeTaskName,
            cargoPackage = "$cargoPackagePrefix-jni",
            nativeLibraryName = "${nativeLibraryPrefix}_jni",
            rustSourceDirectory = rustSourceDirectory,
            targets = androidTargets,
        )
        configureAndroidPackaging(
            nativeTaskName = nativeTaskName,
            prepareTasks = androidPrepareTasks,
        )

        val appleTargets = appleNativeTargets()
        val appleCargoTasks = registerAppleLibraries(
            nativeTaskName = nativeTaskName,
            cargoPackage = "$cargoPackagePrefix-c",
            nativeLibraryName = "${nativeLibraryPrefix}_c",
            rustSourceDirectory = rustSourceDirectory,
            targets = appleTargets,
        )
        configureAppleInterop(
            moduleName = moduleName,
            moduleTaskName = moduleTaskName,
            nativeTaskName = nativeTaskName,
            rustSourceDirectory = rustSourceDirectory,
            targets = appleTargets,
            cargoTasks = appleCargoTasks,
        )

        val compileAndroidAll = tasks.register("compile${nativeTaskName}AndroidAll") {
            group = "build"
            description =
                "Builds and verifies $nativeTaskName JNI libraries for every supported Android ABI."
            dependsOn(androidPrepareTasks)
        }
        val compileAppleAll = registerAppleAggregateTasks(
            nativeTaskName = nativeTaskName,
            cargoTasks = appleCargoTasks,
        )
        tasks.register("$desktopCompileTaskName${hostPlatform.name}") {
            group = "build"
            description =
                "Builds the $nativeTaskName JNI library for the current ${hostPlatform.name} host."
            dependsOn(desktopCompileTaskName)
        }
        tasks.register("compile${nativeTaskName}All") {
            group = "build"
            description =
                "Builds $nativeTaskName artifacts for Android, the current Desktop host, and Apple."
            dependsOn(compileAndroidAll)
            dependsOn(compileAppleAll)
            dependsOn(desktopCompileTaskName)
        }
        tasks.matching { task -> task.name == "assemble" }.configureEach {
            dependsOn(desktopCompileTaskName)
        }
    }

    private fun Project.registerAndroidLibraries(
        nativeTaskName: String,
        cargoPackage: String,
        nativeLibraryName: String,
        rustSourceDirectory: org.gradle.api.file.Directory,
        targets: List<AndroidNativeTarget>,
    ): List<TaskProvider<PrepareNativeLibraryTask>> {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        val androidMinSdk = libs.findVersion("androidMinSdk").get().requiredVersion.toInt()
        val androidNdk = libs.findVersion("androidNdk").get().requiredVersion

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
                    rootDir = rootProject.projectDir,
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
                sourceFiles.from(
                    fileTree(rustSourceDirectory) {
                        exclude("target/**", "**/target/**")
                    },
                )
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
                                rootDir = rootProject.projectDir,
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
