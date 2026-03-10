package binaries

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

abstract class CargoBuildTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val cargoTargetDir: DirectoryProperty

    @get:Internal
    abstract val outputBinary: RegularFileProperty

    @get:Input
    abstract val rustTarget: Property<String>

    @TaskAction
    fun action() {
        val srcDir = sourceDir.get().asFile
        logger.lifecycle("Building Cargo project for target: ${rustTarget.get()}")

        execOperations.exec {
            workingDir = srcDir
            environment("CARGO_TARGET_DIR", cargoTargetDir.get().asFile.absolutePath)
            commandLine(
                "cargo", "build",
                "--release",
                "--target", rustTarget.get(),
            )
        }

        val output = outputBinary.get().asFile
        require(output.exists()) {
            "Cargo output was not produced at ${output.absolutePath}"
        }
    }
}
