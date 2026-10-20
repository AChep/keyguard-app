package com.artemchep.keyguard.buildplugins.androidagent

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

@DisableCachingByDefault(because = "Packages external binaries into Termux .deb artifacts.")
abstract class CreateTermuxPackageTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFile
    abstract val sourceBinary: RegularFileProperty

    @get:InputFile
    abstract val packageVersionFile: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val packageDescription: ListProperty<String>

    @get:Input
    abstract val packageDepends: ListProperty<String>

    @get:Input
    abstract val rustTarget: Property<String>

    @get:Input
    abstract val termuxArch: Property<String>

    @get:Input
    abstract val termuxCreatePackageBin: Property<String>

    @get:OutputDirectory
    abstract val packageDirectory: DirectoryProperty

    @get:OutputFile
    abstract val packageOutputFile: RegularFileProperty

    init {
        group = "build"
        description = "Creates a Termux .deb package for one agent helper target."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun action() {
        val source = sourceBinary.get().asFile
        require(source.isFile) {
            "Built binary not found at ${source.absolutePath}. Run the helper's compile task first."
        }

        val version = packageVersionFile.get().asFile.readText().trim()
        if (version.isBlank()) {
            throw GradleException("Resolved package version file is empty.")
        }
        AndroidTermuxAgentPackaging.requireDebianVersion(version)

        val packageDir = packageDirectory.get().asFile
        if (packageDir.exists()) {
            packageDir.deleteRecursively()
        }

        val filesDir = File(packageDir, "files")
        val binDir = File(filesDir, "bin")
        require(binDir.mkdirs() || binDir.isDirectory) {
            "Failed to create staging directory ${binDir.absolutePath}"
        }

        val stagedBinary = File(binDir, packageName.get())
        source.copyTo(stagedBinary, overwrite = true)
        require(stagedBinary.setExecutable(true, false)) {
            "Failed to mark staged binary as executable: ${stagedBinary.absolutePath}"
        }

        val outputDeb = packageOutputFile.get().asFile
        outputDeb.parentFile.mkdirs()
        if (outputDeb.exists()) {
            outputDeb.delete()
        }

        val manifestFile = File(packageDir, "package.json")
        manifestFile.writeText(
            createManifestJson(
                version = version,
                termuxArch = termuxArch.get(),
                filesDir = filesDir,
                outputDeb = outputDeb,
            ),
        )

        try {
            execOperations.exec {
                workingDir = packageDir
                commandLine(
                    termuxCreatePackageBin.get(),
                    manifestFile.absolutePath,
                )
            }
        } catch (e: Exception) {
            val message = "Failed to run `${termuxCreatePackageBin.get()}` for ${rustTarget.get()}. " +
                "Install termux-create-package and ensure it is on PATH."
            throw GradleException(message, e)
        }

        require(outputDeb.isFile) {
            "Termux package was not produced at ${outputDeb.absolutePath}"
        }
    }

    private fun createManifestJson(
        version: String,
        termuxArch: String,
        filesDir: File,
        outputDeb: File,
    ): String {
        val depends = packageDepends.get()
        val dependsField = if (depends.isNotEmpty()) {
            "\"Depends\": \"${jsonEscape(depends.joinToString(", "))}\","
        } else {
            ""
        }
        return """
        {
          "control": {
            "Package": "${jsonEscape(packageName.get())}",
            "Version": "${jsonEscape(version)}",
            "Architecture": "${jsonEscape(termuxArch)}",
            "Maintainer": "${jsonEscape(AndroidTermuxAgentPackaging.MAINTAINER)}",
            "Section": "utils",
            "Priority": "optional",
            "Homepage": "${jsonEscape(AndroidTermuxAgentPackaging.HOMEPAGE)}",
            $dependsField
            "Description": [
              ${packageDescription.get().joinToString(",\n              ") { "\"${jsonEscape(it)}\"" }}
            ]
          },
          "deb_name": "${jsonEscape(outputDeb.name)}",
          "installation_prefix": "${jsonEscape(AndroidTermuxAgentPackaging.TERMUX_PREFIX)}",
          "files_dir": "${jsonEscape(filesDir.absolutePath)}",
          "deb_dir": "${jsonEscape(outputDeb.parentFile.absolutePath)}",
          "data_files": {
            "bin/${jsonEscape(packageName.get())}": {
              "source": "bin/${jsonEscape(packageName.get())}"
            }
          }
        }
    """.trimIndent()
    }

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}
