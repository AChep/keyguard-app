package com.artemchep.keyguard.buildplugins.androidagent

import com.artemchep.keyguard.buildplugins.cargo.CargoBuildTask
import com.artemchep.keyguard.buildplugins.cargo.SignAndCopyBinaryTask
import com.artemchep.keyguard.buildplugins.libs
import com.artemchep.keyguard.buildplugins.version
import com.artemchep.keyguard.buildplugins.versionInt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

class AndroidSshAgentPlugin : AndroidTermuxAgentPlugin(
    AndroidTermuxAgent(
        packageName = "keyguard-android-ssh-agent",
        taskStem = "AndroidSshAgent",
        description = listOf(
            "Standalone SSH agent bridge for Keyguard on Android/Termux.",
            " It forwards SSH agent requests from Termux to the Keyguard app.",
        ),
    ),
)

class AndroidGpgAgentPlugin : AndroidTermuxAgentPlugin(
    AndroidTermuxAgent(
        packageName = "keyguard-android-gpg-agent",
        taskStem = "AndroidGpgAgent",
        description = listOf(
            "Standalone GPG agent bridge for Keyguard on Android/Termux.",
            " It forwards GnuPG agent requests from Termux to the Keyguard app.",
        ),
        // The helper resolves its socket with gpgconf.
        depends = listOf("gnupg"),
    ),
)

/** Builds a Rust agent helper for every Termux architecture and packages it as a `.deb`. */
abstract class AndroidTermuxAgentPlugin(
    private val agent: AndroidTermuxAgent,
) : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("base")

        val cargoSourceDir = "src"
        val cargoBinaryName = agent.packageName
        val termuxTargets = AndroidTermuxAgentPackaging.supportedTargets
        val androidMinSdk = libs.versionInt("androidMinSdk")
        val androidNdk = libs.version("androidNdk")
        val appMarketingVersion = libs.version("appVersionName")
        val termuxPackageVersion = AndroidTermuxAgentPackaging.resolvePackageVersion(
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
                    rootDir = rootDir,
                    localPropertiesFile = null,
                    rustTarget = rustTarget,
                    androidApiLevel = androidMinSdk,
                    ndkVersion = androidNdk,
                )
            },
        )

        val resolvePackageVersion = tasks.register<ResolveTermuxPackageVersionTask>(
            "resolve${agent.taskStem}PackageVersion",
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
                this.cargoTargetDir.set(cargoTargetDir)
                rustTarget.set(targetInfo.rustTarget)
                outputBinary.set(cargoOutputBinary)
                configureAndroidLinker(androidLinkerExecutableFor(targetInfo.rustTarget))
            }

            compileTasks[targetInfo.rustTarget] = tasks.register<SignAndCopyBinaryTask>("compile$suffix") {
                dependsOn(cargoBuild)
                sourceBinary.set(cargoBuild.flatMap { it.outputBinary })
                destinationDirectory.set(
                    layout.buildDirectory.dir("compiled-binaries/${targetInfo.rustTarget}"),
                )
                destinationRelativePath.set(cargoBinaryName)
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
                packageName.set(agent.packageName)
                packageDescription.set(agent.description)
                packageDepends.set(agent.depends)
                packageVersionFile.set(resolvePackageVersion.flatMap { it.outputFile })
                rustTarget.set(targetInfo.rustTarget)
                termuxArch.set(targetInfo.termuxArch)
                termuxCreatePackageBin.set(termuxCreatePackageBinProvider)
                packageDirectory.set(layout.buildDirectory.dir("termux/${targetInfo.termuxArch}"))
                packageOutputFile.set(
                    layout.buildDirectory.file(
                        "termux/debs/${
                            AndroidTermuxAgentPackaging.debFileName(
                                packageName = agent.packageName,
                                version = termuxPackageVersion,
                                termuxArch = targetInfo.termuxArch,
                            )
                        }",
                    ),
                )
            }
        }

        tasks.register("compile${agent.taskStem}All") {
            group = "build"
            dependsOn(compileTasks.values)
        }

        tasks.register("package${agent.taskStem}TermuxAll") {
            group = "build"
            dependsOn(resolvePackageVersion)
            dependsOn(packageTasks)
        }

        Unit
    }
}
