package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import javax.inject.Inject

@DisableCachingByDefault(because = "Verifies an external native binary and has no outputs")
abstract class VerifyElfPageAlignmentTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val readElfExecutable: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val binary: RegularFileProperty

    @TaskAction
    fun action() {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(
                readElfExecutable.get().asFile.absolutePath,
                "--program-headers",
                "--wide",
                binary.get().asFile.absolutePath,
            )
            standardOutput = output
        }

        val loadAlignments = output.toString()
            .lineSequence()
            .map(String::trim)
            .filter { line -> line.startsWith("LOAD ") }
            .mapNotNull { line ->
                line.split(Regex("\\s+"))
                    .lastOrNull()
                    ?.removePrefix("0x")
                    ?.toLongOrNull(radix = 16)
            }
            .toList()

        require(loadAlignments.isNotEmpty()) {
            "No ELF LOAD segments found in ${binary.get().asFile.absolutePath}"
        }
        require(loadAlignments.all { alignment -> alignment >= ANDROID_PAGE_ALIGNMENT }) {
            "Native library is not 16 KiB page aligned: ${binary.get().asFile.absolutePath}; " +
                "LOAD alignments=${loadAlignments.joinToString { alignment -> "0x${alignment.toString(16)}" }}"
        }
    }

    private companion object {
        const val ANDROID_PAGE_ALIGNMENT = 16L * 1024L
    }
}
