package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

@DisableCachingByDefault(because = "Checks the local Rust toolchain and has no outputs")
abstract class VerifyRustTargetInstalledTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:Input
    abstract val rustTarget: Property<String>

    @TaskAction
    fun action() {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            workingDir(sourceDir.get().asFile)
            commandLine("rustc", "--print", "sysroot")
            standardOutput = output
        }

        val sysroot = File(output.toString().trim())
        val target = rustTarget.get()
        val targetLibraries = File(sysroot, "lib/rustlib/$target/lib")
        require(targetLibraries.isDirectory) {
            "Rust target '$target' is not installed for the toolchain selected by " +
                "${sourceDir.get().asFile.absolutePath}. Install it with `rustup target add $target` " +
                "and retry the Gradle task. Expected target libraries at ${targetLibraries.absolutePath}."
        }
    }
}
