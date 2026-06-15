package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register

class CargoCommonPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("base")

        val extension = extensions.create<CargoCommonExtension>("keyguardCargo").apply {
            certIdentity.convention(providers.gradleProperty("cert_identity"))
        }
        val bundledElements = createBundledAppResourcesElements()

        afterEvaluate {
            registerTasks(extension, bundledElements)
        }
    }

    private fun Project.createBundledAppResourcesElements(): Configuration =
        configurations.create(BUNDLED_APP_RESOURCES_ELEMENTS_CONFIGURATION_NAME) {
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes.attribute(
                Usage.USAGE_ATTRIBUTE,
                objects.named(Usage::class.java, "keyguard-bundled-app-resources"),
            )
        }

    private fun Project.registerTasks(
        extension: CargoCommonExtension,
        bundledElements: Configuration,
    ) {
        val cargoTargetDir = layout.buildDirectory.dir("cargo-target")
        val sourceDirPath = extension.sourceDir.get().asFile
        val sourceFileTrees = listOf(
            fileTree(sourceDirPath) {
                exclude("target/**")
            },
        ) + extension.extraSourceInputs.files.map { sourcePath ->
            fileTree(sourcePath) {
                exclude("target/**")
            }
        }

        val cargoOutputBinary = layout.buildDirectory.file(
            "cargo-target/${extension.rustTarget.get()}/release/${extension.cargoBinaryName.get()}",
        )

        val cargoBuild = tasks.register<CargoBuildTask>(extension.cargoTaskName.get()) {
            sourceDir.set(extension.sourceDir)
            sourceFiles.from(sourceFileTrees)
            this.cargoTargetDir.set(cargoTargetDir)
            rustTarget.set(extension.rustTarget)
            outputBinary.set(cargoOutputBinary)
        }

        val compileTask = tasks.register<SignAndCopyBinaryTask>(extension.compileTaskName.get()) {
            dependsOn(cargoBuild)
            sourceBinary.set(cargoBuild.flatMap { it.outputBinary })
            destinationBinary.set(
                layout.buildDirectory.file(
                    "bin/${extension.composeResourceDir.get()}/${extension.packagedBinaryName.get()}",
                ),
            )
            certIdentity.set(extension.certIdentity)
            platformMacOs.set(extension.platformMacOs)
            platformWindows.set(extension.platformWindows)
            markExecutable.set(extension.markExecutable)
        }

        artifacts.add(bundledElements.name, layout.buildDirectory.dir("bin")) {
            type = "directory"
            builtBy(compileTask)
        }
    }

    companion object {
        const val BUNDLED_APP_RESOURCES_ELEMENTS_CONFIGURATION_NAME = "bundledAppResourcesElements"
    }
}
