package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import java.io.File

abstract class RustWasmLibraryExtension {
    abstract val sdkDirectory: DirectoryProperty
    abstract val sdkVersion: Property<String>
    abstract val sdkInstallerRevision: Property<String>
    abstract val sdkInstallerChecksum: Property<String>
    abstract val pythonExecutable: Property<String>
    abstract val factoryName: Property<String>
    abstract val exportedFunctions: ListProperty<String>
    abstract val linkerArguments: ListProperty<String>
    abstract val environmentVariables: MapProperty<String, String>
    abstract val packageFiles: ConfigurableFileCollection
    abstract val clippyPackages: ListProperty<String>
}

/** Opt-in Emscripten producer; registering it never installs or builds a toolchain. */
class RustWasmLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val naming = RustModuleNaming(this)
        val prefix = naming.nativeTaskName + "Wasm"
        val extension = extensions.create<RustWasmLibraryExtension>("keyguardRustWasm")
        val sdkOverride = providers.gradleProperty("keyguard.wasm.emsdkDir")
        val projectDirectory = layout.projectDirectory.asFile
        val toolchainCache = gradle.gradleUserHomeDir.resolve("keyguard/emsdk")
        extension.apply {
            sdkVersion.convention("4.0.23")
            sdkInstallerRevision.convention("708dcbcc9ee23bc7d2dad44fc90db377e38b771c")
            sdkInstallerChecksum.convention("4bad50333627618a6c5b08ccac8ff282fa70367af4aeacb55c81bebe80488870")
            sdkDirectory.convention(
                layout.dir(sdkOverride.map { path ->
                    File(path).let { if (it.isAbsolute) it else projectDirectory.resolve(it) }
                }.orElse(sdkVersion.zip(sdkInstallerRevision) { version, revision ->
                    toolchainCache.resolve("$version-${revision.take(8)}")
                })),
            )
            pythonExecutable.convention(providers.gradleProperty("keyguard.wasm.python").orElse(
                if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
            ))
            factoryName.convention("create${naming.moduleTaskName}")
            exportedFunctions.convention(emptyList())
            linkerArguments.convention(emptyList())
            environmentVariables.convention(emptyMap())
            clippyPackages.convention(listOf("${naming.cargoPackagePrefix}-c"))
        }
        val sdk = tasks.register<SetupEmscriptenTask>("setup${prefix}Sdk") {
            group = "build setup"
            sdkDirectory.set(extension.sdkDirectory)
            version.set(extension.sdkVersion)
            installerRevision.set(extension.sdkInstallerRevision)
            installerChecksum.set(extension.sdkInstallerChecksum)
            pythonExecutable.set(extension.pythonExecutable)
            managed.set(sdkOverride.map { false }.orElse(true))
            offline.set(gradle.startParameter.isOffline)
        }
        val environment = extension.sdkDirectory.zip(extension.pythonExecutable) { directory, python ->
            directory.asFile to python
        }.zip(providers.environmentVariable("PATH").orElse("")) { (directory, python), path ->
            emscriptenEnvironment(directory, python, path) + mapOf(
                "BINDGEN_EXTRA_CLANG_ARGS_${EMSCRIPTEN_TARGET.replace('-', '_')}" to bindgenSysrootArgument(directory),
            )
        }
        val verifyTarget = tasks.register<VerifyRustTargetInstalledTask>("verify${prefix}RustTarget") {
            sourceDir.set(naming.rustSourceDirectory)
            rustTarget.set(EMSCRIPTEN_TARGET)
        }
        val cargo = tasks.register<CargoBuildTask>("cargoBuild$prefix") {
            dependsOn(sdk, verifyTarget)
            sourceDir.set(naming.rustSourceDirectory)
            rustTarget.set(EMSCRIPTEN_TARGET)
            cargoPackage.set("${naming.cargoPackagePrefix}-c")
            cargoArguments.addAll("--locked", "--jobs", "4")
            offline.set(cargoOfflineProvider(naming.moduleTaskName))
            cargoTargetDir.set(layout.buildDirectory.dir("wasm/cargo"))
            outputBinary.set(cargoTargetDir.file("$EMSCRIPTEN_TARGET/release/lib${naming.nativeLibraryPrefix}_c.a"))
            environmentVariables.putAll(environment)
            environmentVariables.putAll(extension.environmentVariables)
        }
        val link = tasks.register<EmscriptenLinkTask>("link$prefix") {
            group = "build"
            dependsOn(sdk)
            archive.set(cargo.flatMap { it.outputBinary })
            compiler.set(extension.sdkDirectory.file("upstream/emscripten/emcc.py"))
            sdkConfiguration.set(extension.sdkDirectory.file(".emscripten"))
            sdkVersion.set(extension.sdkVersion)
            pythonExecutable.set(extension.pythonExecutable)
            outputName.set(naming.nativeLibraryPrefix)
            factoryName.set(extension.factoryName)
            exportedFunctions.set(extension.exportedFunctions)
            linkerArguments.set(extension.linkerArguments)
            environmentVariables.set(environment)
            outputDirectory.set(layout.buildDirectory.dir("wasm/linked"))
        }
        tasks.register<Sync>("build$prefix") {
            group = "build"
            description = "Stages the Rust/Emscripten runtime package."
            from(link.flatMap { it.outputDirectory })
            from(extension.packageFiles)
            into(layout.buildDirectory.dir("wasm/npm"))
        }
        tasks.register<EmscriptenClippyTask>("clippy$prefix") {
            group = "verification"
            dependsOn(sdk, verifyTarget)
            mustRunAfter(cargo)
            sourceDirectory.set(naming.rustSourceDirectory)
            targetDirectory.set(layout.buildDirectory.dir("wasm/cargo"))
            packages.set(extension.clippyPackages)
            offline.set(cargoOfflineProvider(naming.moduleTaskName))
            environmentVariables.putAll(environment)
            environmentVariables.putAll(extension.environmentVariables)
        }
        Unit
    }
}
