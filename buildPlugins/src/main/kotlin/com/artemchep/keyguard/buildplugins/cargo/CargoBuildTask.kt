package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
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

    @get:OutputDirectory
    abstract val cargoTargetDir: DirectoryProperty

    @get:OutputFile
    abstract val outputBinary: RegularFileProperty

    @get:Input
    abstract val rustTarget: Property<String>

    init {
        outputs.upToDateWhen { false }
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
            commandLine(
                "cargo", "build",
                "--release",
                "--target", target,
            )
        }

        val output = outputBinary.get().asFile
        require(output.exists()) {
            "Cargo output was not produced at ${output.absolutePath}"
        }
    }
}
