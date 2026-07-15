package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Builds external Cargo artifacts")
abstract class CargoBuildTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:InputFile
    @get:Optional
    abstract val linkerExecutable: RegularFileProperty

    // Cargo mutates ephemeral metadata inside this directory while Gradle snapshots task state.
    // Track the declared final artifact instead of the entire shared build tree.
    @get:LocalState
    abstract val cargoTargetDir: DirectoryProperty

    @get:OutputFile
    abstract val outputBinary: RegularFileProperty

    @get:Input
    abstract val rustTarget: Property<String>

    @get:Input
    @get:Optional
    abstract val cargoPackage: Property<String>

    @get:Input
    abstract val cargoArguments: ListProperty<String>

    @get:Input
    abstract val environmentVariables: MapProperty<String, String>

    @get:Input
    abstract val offline: Property<Boolean>

    init {
        outputs.upToDateWhen { false }
        cargoArguments.convention(emptyList())
        environmentVariables.convention(emptyMap())
        offline.convention(false)
    }

    fun configureAndroidLinker(linkerExecutable: Provider<out RegularFile>) {
        this.linkerExecutable.set(linkerExecutable)
    }

    @TaskAction
    fun action() {
        val srcDir = sourceDir.get().asFile
        val target = rustTarget.get()
        logger.lifecycle("Building Cargo project for target: $target")

        execOperations.exec {
            workingDir = srcDir
            environment("CARGO_TARGET_DIR", cargoTargetDir.get().asFile.absolutePath)
            linkerExecutable.orNull?.asFile?.let { linkerExecutable ->
                val name = "CARGO_TARGET_${target.uppercase().replace('-', '_').replace('.', '_')}_LINKER"
                environment(name, linkerExecutable.absolutePath)
            }
            environment(environmentVariables.get())
            commandLine(cargoCommandLine(target))
        }

        val output = outputBinary.get().asFile
        require(output.exists()) {
            "Cargo output was not produced at ${output.absolutePath}"
        }
    }

    internal fun cargoCommandLine(target: String = rustTarget.get()): List<String> = buildList {
        add("cargo")
        add("build")
        add("--release")
        add("--target")
        add(target)
        cargoPackage.orNull
            ?.takeIf(String::isNotBlank)
            ?.let { packageName ->
                add("--package")
                add(packageName)
            }
        if (offline.get()) {
            add("--offline")
        }
        addAll(cargoArguments.get())
    }
}
