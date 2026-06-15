package com.artemchep.keyguard.buildplugins.androidssh

import com.artemchep.keyguard.buildplugins.cargo.CargoBuildTask
import com.artemchep.keyguard.buildplugins.cargo.SignAndCopyBinaryTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

class AndroidSshAgentPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("base")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        val cargoSourceDir = "src"
        val cargoBinaryName = AndroidSshAgentTermuxPackaging.PACKAGE_NAME
        val termuxTargets = AndroidSshAgentTermuxPackaging.supportedTargets
        val androidMinSdk = libs.findVersion("androidMinSdk").get().requiredVersion.toInt()
        val appMarketingVersion = libs.findVersion("appVersionName").get().requiredVersion
        val termuxPackageVersion = AndroidSshAgentTermuxPackaging.resolvePackageVersion(
            marketingVersion = appMarketingVersion,
        )
        val termuxCreatePackageBinProvider = providers.environmentVariable("TERMUX_CREATE_PACKAGE_BIN")
            .orElse("termux-create-package")

        fun String.toTaskSuffix(): String = split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString("") { part ->
                part.replaceFirstChar(Char::uppercaseChar)
            }

        fun androidLinkerExecutableFor(rustTarget: String) = layout.file(
            providers.provider {
                AndroidCargoEnvironment.resolveLinkerExecutable(
                    rootDir = rootProject.projectDir,
                    localPropertiesFile = null,
                    rustTarget = rustTarget,
                    androidApiLevel = androidMinSdk,
                )
            },
        )

        val resolvePackageVersion = tasks.register<ResolveAndroidSshAgentPackageVersionTask>(
            "resolveAndroidSshAgentPackageVersion",
        ) {
            marketingVersion.set(appMarketingVersion)
            outputFile.set(layout.buildDirectory.file("termux/package-version.txt"))
        }

        val compileTasks = mutableMapOf<String, TaskProvider<SignAndCopyBinaryTask>>()
        termuxTargets.forEach { targetInfo ->
            val suffix = targetInfo.rustTarget.toTaskSuffix()
            val cargoTargetDir = layout.buildDirectory.dir("cargo-target/${targetInfo.rustTarget}")
            val cargoOutputBinary = cargoTargetDir.map { dir ->
                dir.file("${targetInfo.rustTarget}/release/$cargoBinaryName")
            }
            val cargoBuild = tasks.register<CargoBuildTask>("cargoBuild$suffix") {
                sourceDir.set(project.file(cargoSourceDir))
                sourceFiles.from(
                    project.fileTree(cargoSourceDir) {
                        exclude("target/**")
                    },
                    rootProject.fileTree("commonSshAgent") {
                        exclude("target/**")
                    },
                )
                this.cargoTargetDir.set(cargoTargetDir)
                rustTarget.set(targetInfo.rustTarget)
                outputBinary.set(cargoOutputBinary)
                configureAndroidLinker(androidLinkerExecutableFor(targetInfo.rustTarget))
            }

            compileTasks[targetInfo.rustTarget] = tasks.register<SignAndCopyBinaryTask>("compile$suffix") {
                dependsOn(cargoBuild)
                sourceBinary.set(cargoBuild.flatMap { it.outputBinary })
                destinationBinary.set(
                    layout.buildDirectory.file("bin/${targetInfo.rustTarget}/$cargoBinaryName"),
                )
                platformMacOs.set(false)
                platformWindows.set(false)
                markExecutable.set(true)
            }
        }

        val packageTasks = termuxTargets.map { targetInfo ->
            val suffix = targetInfo.rustTarget.toTaskSuffix()
            val compileTask = checkNotNull(compileTasks[targetInfo.rustTarget])

            tasks.register<CreateTermuxPackageTask>("package${suffix}Termux") {
                dependsOn(compileTask)
                dependsOn(resolvePackageVersion)

                sourceBinary.set(compileTask.flatMap { it.destinationBinary })
                packageVersionFile.set(resolvePackageVersion.flatMap { it.outputFile })
                rustTarget.set(targetInfo.rustTarget)
                termuxArch.set(targetInfo.termuxArch)
                termuxCreatePackageBin.set(termuxCreatePackageBinProvider)
                packageDirectory.set(layout.buildDirectory.dir("termux/${targetInfo.termuxArch}"))
                packageOutputFile.set(
                    layout.buildDirectory.file(
                        "termux/debs/${AndroidSshAgentTermuxPackaging.debFileName(termuxPackageVersion, targetInfo.termuxArch)}",
                    ),
                )
            }
        }

        tasks.register("compileAndroidSshAgentAll") {
            group = "build"
            dependsOn(compileTasks.values)
        }

        tasks.register("packageAndroidSshAgentTermuxAll") {
            group = "build"
            dependsOn(resolvePackageVersion)
            dependsOn(packageTasks)
        }

        Unit
    }
}
