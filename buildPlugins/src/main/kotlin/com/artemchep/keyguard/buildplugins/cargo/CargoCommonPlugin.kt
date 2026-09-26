package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register

class CargoCommonPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("base")

        val hostPlatform = detectHostPlatform()
        extensions.create<CargoCommonExtension>("keyguardCargo", project).apply {
            sourceDir.convention(layout.projectDirectory.dir("src"))
            rustTarget.convention(hostPlatform.rustTarget)
            packagedBinaryName.convention(cargoBinaryName)
            composeResourceDir.convention(hostPlatform.composeResourceDir)
            platformMacOs.convention(hostPlatform.isMacOs)
            platformWindows.convention(hostPlatform.isWindows)
            certIdentity.convention(providers.gradleProperty("cert_identity"))
        }
        // Gradle 9.6+ reads a `-resources` suffix on a Usage value as a legacy alias and fails
        // on it in Gradle 10, so the artifact kind is carried by LibraryElements instead.
        createNativeElements(
            BUNDLED_APP_RESOURCES_ELEMENTS_CONFIGURATION_NAME,
            usage = "keyguard-bundled-app",
            libraryElements = LibraryElements.RESOURCES,
        )
        createNativeElements(
            NATIVE_DESKTOP_LIBRARY_ELEMENTS_CONFIGURATION_NAME,
            usage = "keyguard-native-desktop-library",
        )
    }

    private fun Project.createNativeElements(
        name: String,
        usage: String,
        libraryElements: String? = null,
    ) {
        configurations.create(name) {
            isCanBeConsumed = true
            isCanBeResolved = false
            isCanBeDeclared = false
            attributes.attribute(
                Usage.USAGE_ATTRIBUTE,
                objects.named(Usage::class.java, usage),
            )
            if (libraryElements != null) {
                attributes.attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements::class.java, libraryElements),
                )
            }
        }
    }

    companion object {
        const val BUNDLED_APP_RESOURCES_ELEMENTS_CONFIGURATION_NAME = "bundledAppResourcesElements"
        const val NATIVE_DESKTOP_LIBRARY_ELEMENTS_CONFIGURATION_NAME = "nativeDesktopLibraryElements"
    }
}

class CargoTasks internal constructor(
    val build: TaskProvider<CargoBuildTask>,
    val compile: TaskProvider<SignAndCopyBinaryTask>,
)

internal fun Project.registerCargoTasks(
    extension: CargoCommonExtension,
    compileTaskName: String,
    cargoTaskName: String,
): CargoTasks {
    val cargoTargetDir = layout.buildDirectory.dir("cargo-target")
    val bundledAppResourcesDir = layout.buildDirectory.dir("bundled-app-resources")
    val cargoOutputBinary = layout.buildDirectory.file(
        extension.rustTarget.zip(extension.cargoBinaryName) { rustTarget, binaryName ->
            "cargo-target/$rustTarget/release/$binaryName"
        },
    )

    val cargoBuild = tasks.register<CargoBuildTask>(cargoTaskName) {
        sourceDir.set(extension.sourceDir)
        sourceFiles.from(
            extension.sourceDir.map { directory ->
                directory.asFileTree.matching {
                    exclude("target/**", "**/target/**")
                }
            },
            extension.extraSourceInputs.asFileTree.matching {
                exclude("target/**", "**/target/**")
            },
        )
        this.cargoTargetDir.set(cargoTargetDir)
        rustTarget.set(extension.rustTarget)
        outputBinary.set(cargoOutputBinary)
        cargoPackage.set(extension.cargoPackage)
        cargoArguments.set(extension.cargoArguments)
        environmentVariables.set(extension.environmentVariables)
    }

    val compileTask = tasks.register<SignAndCopyBinaryTask>(compileTaskName) {
        sourceBinary.set(cargoBuild.flatMap { it.outputBinary })
        destinationDirectory.set(bundledAppResourcesDir)
        destinationRelativePath.set(
            extension.composeResourceDir.zip(extension.packagedBinaryName) { resourceDir, binaryName ->
                "$resourceDir/$binaryName"
            },
        )
        certIdentity.set(extension.certIdentity)
        platformMacOs.set(extension.platformMacOs)
        platformWindows.set(extension.platformWindows)
        markExecutable.set(extension.markExecutable)
    }

    artifacts.add(
        CargoCommonPlugin.BUNDLED_APP_RESOURCES_ELEMENTS_CONFIGURATION_NAME,
        bundledAppResourcesDir,
    ) {
        type = "directory"
        builtBy(compileTask)
    }
    // Tests keep loading the original Cargo artifact. The compile dependency preserves the
    // existing signing/staging checks while letting consumers discover the producer's path.
    artifacts.add(
        CargoCommonPlugin.NATIVE_DESKTOP_LIBRARY_ELEMENTS_CONFIGURATION_NAME,
        cargoBuild.flatMap { it.outputBinary },
    ) {
        builtBy(compileTask)
    }
    return CargoTasks(cargoBuild, compileTask)
}
