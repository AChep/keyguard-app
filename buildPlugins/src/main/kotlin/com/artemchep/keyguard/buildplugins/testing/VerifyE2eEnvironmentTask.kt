package com.artemchep.keyguard.buildplugins.testing

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.time.Duration

enum class E2eToolchain {
    GPG,
    SSH,
    PYKEEPASS,
    WEBDAV,
}

/** Checks the host tools only when its associated E2E suite is requested. */
@DisableCachingByDefault(because = "Checks external tools installed on the current host")
abstract class VerifyE2eEnvironmentTask : DefaultTask() {
    @get:Input
    abstract val toolchain: Property<E2eToolchain>

    @get:Input
    @get:Optional
    abstract val gpgBinDirectory: Property<String>

    @get:Input
    abstract val pythonExecutable: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pythonDriver: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val pythonSetupHint: Property<String>

    @get:Input
    abstract val searchPath: Property<String>

    @get:Input
    abstract val pathExtensions: Property<String>

    @get:Input
    abstract val windows: Property<Boolean>

    init {
        group = "verification"
        description = "Checks the external tools required by this E2E suite."
        gpgBinDirectory.convention(
            project.providers.systemProperty("keyguard.gpg.binDir")
                .orElse(project.providers.environmentVariable("KEYGUARD_GPG_BIN_DIR")),
        )
        pythonExecutable.convention("python3")
        searchPath.convention(project.providers.environmentVariable("PATH").orElse(""))
        pathExtensions.convention(project.providers.environmentVariable("PATHEXT").orElse(".COM;.EXE;.BAT;.CMD"))
        windows.convention(
            project.providers.systemProperty("os.name").map {
                it.startsWith("Windows", ignoreCase = true)
            },
        )
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        val runner = E2eCommandRunner(temporaryDir)
        when (toolchain.get()) {
            E2eToolchain.GPG -> {
                listOf("gpg", "gpgconf", "cargo").forEach { tool ->
                    val command = if (tool == "cargo") tool else gpgTool(tool, gpgBinDirectory.orNull, windows.get())
                    runner.requireRuns(
                        command = listOf(command, "--version"),
                        timeout = Duration.ofSeconds(15),
                        requirement = "GPG E2E test requires '$tool'",
                    )
                }
            }
            E2eToolchain.SSH -> {
                listOf("ssh-add", "ssh-keygen").forEach { tool ->
                    requireExecutableOnPath(tool, searchPath.get(), pathExtensions.get(), windows.get())
                }
                runner.requireRuns(
                    command = listOf("cargo", "--version"),
                    timeout = Duration.ofSeconds(15),
                    requirement = "SSH E2E test requires 'cargo' on PATH",
                )
            }
            E2eToolchain.PYKEEPASS -> runner.requireRuns(
                command = listOf(pythonExecutable.get(), pythonDriver.get().asFile.absolutePath, "doctor"),
                timeout = Duration.ofSeconds(30),
                requirement = "KDBX E2E tests require a working pykeepass runtime",
                setupHint = pythonSetupHint.get(),
            )
            E2eToolchain.WEBDAV -> {
                val output = runner.requireRuns(
                    command = listOf("webdav", "version"),
                    timeout = Duration.ofSeconds(10),
                    requirement = "Expected hacdias/webdav to be installed as 'webdav' on PATH",
                )
                requireWebDav5(output)
            }
        }
    }
}
