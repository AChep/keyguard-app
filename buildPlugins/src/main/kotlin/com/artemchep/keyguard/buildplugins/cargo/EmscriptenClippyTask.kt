package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import javax.inject.Inject

@UntrackedTask(because = "Cargo tracks Clippy's Rust inputs")
abstract class EmscriptenClippyTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Internal abstract val sourceDirectory: DirectoryProperty
    // Cargo owns this shared cache; Clippy does not produce the link archive.
    @get:Internal abstract val targetDirectory: DirectoryProperty
    @get:Input abstract val packages: ListProperty<String>
    // Feature flags of the build it checks, such as `--no-default-features`.
    @get:Input abstract val cargoArguments: ListProperty<String>
    @get:Input abstract val offline: Property<Boolean>
    @get:Input abstract val environmentVariables: MapProperty<String, String>

    init {
        cargoArguments.convention(emptyList())
    }

    @TaskAction
    fun check() {
        val arguments = listOf("cargo", "clippy", "--release", "--locked", "--target", EMSCRIPTEN_TARGET, "--jobs", "4") +
            packages.get().flatMap { listOf("--package", it) } + cargoArguments.get() +
            (if (offline.get()) listOf("--offline") else emptyList()) + listOf("--no-deps", "--", "-D", "warnings")
        execOperations.exec {
            workingDir(sourceDirectory.get().asFile)
            environment(environmentVariables.get())
            environment("CARGO_TARGET_DIR", targetDirectory.get().asFile.absolutePath)
            commandLine(arguments)
        }
    }
}
