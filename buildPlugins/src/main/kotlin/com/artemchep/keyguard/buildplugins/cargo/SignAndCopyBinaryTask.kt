package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Signs and copies platform-specific binaries with external tools and executable-bit changes.")
abstract class SignAndCopyBinaryTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFile
    abstract val sourceBinary: RegularFileProperty

    @get:OutputFile
    abstract val destinationBinary: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val certIdentity: Property<String>

    @get:Input
    abstract val platformMacOs: Property<Boolean>

    @get:Input
    abstract val platformWindows: Property<Boolean>

    @get:Input
    abstract val markExecutable: Property<Boolean>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun action() {
        val source = sourceBinary.get().asFile
        val identity = certIdentity.orNull?.takeIf(String::isNotBlank)

        require(source.exists()) {
            "Binary must exist before sign-and-copy task is started!"
        }

        val destination = destinationBinary.get().asFile
        destination.parentFile.mkdirs()
        source.copyTo(
            target = destination,
            overwrite = true,
        )

        if (platformMacOs.get() && identity != null) {
            logger.lifecycle("Signing binary with identity ${identity.take(2)}****")
            execOperations.exec {
                commandLine(
                    "codesign",
                    "--force",
                    "--options", "runtime",
                    "--sign", identity,
                    destination.absolutePath,
                )
            }
        }

        if (markExecutable.get() && !platformWindows.get()) {
            require(destination.setExecutable(true, false)) {
                "Failed to mark binary as executable: ${destination.absolutePath}"
            }
        }
    }
}
