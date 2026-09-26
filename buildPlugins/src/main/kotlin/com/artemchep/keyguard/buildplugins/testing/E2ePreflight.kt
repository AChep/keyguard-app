package com.artemchep.keyguard.buildplugins.testing

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

internal class E2eCommandRunner(private val outputDirectory: File) {
    fun requireRuns(
        command: List<String>,
        timeout: Duration,
        requirement: String,
        setupHint: String = "",
    ): String {
        val displayCommand = command.joinToString(" ")
        val hint = setupHint.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
        outputDirectory.mkdirs()
        val outputFile = File.createTempFile("e2e-preflight-", ".log", outputDirectory)
        try {
            val process = try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    // A file keeps a verbose version command from blocking on a full output pipe.
                    .redirectOutput(outputFile)
                    .start()
            } catch (e: Exception) {
                throw GradleException("$requirement, but '$displayCommand' could not be started.$hint", e)
            }
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw GradleException("Timed out while running '$displayCommand'.$hint")
                }
                val output = outputFile.readText().trim()
                if (process.exitValue() != 0) {
                    throw GradleException(
                        "$requirement, but '$displayCommand' exited with ${process.exitValue()}.\n$output$hint",
                    )
                }
                return output
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                }
            }
        } finally {
            outputFile.delete()
        }
    }
}

internal fun gpgTool(tool: String, binDirectory: String?, windows: Boolean): String {
    if (binDirectory == null) return tool
    val executableName = if (windows) "$tool.exe" else tool
    return File(binDirectory, executableName).absolutePath
}

internal fun requireExecutableOnPath(
    tool: String,
    path: String,
    pathExtensions: String,
    windows: Boolean,
) {
    val executableNames = if (windows && !tool.contains('.')) {
        val extensions = pathExtensions.split(';').filter(String::isNotBlank)
        listOf(tool) + extensions.map { tool + it.lowercase() }
    } else {
        listOf(tool)
    }
    val pathSeparator = if (windows) ';' else File.pathSeparatorChar
    val candidates = path.split(pathSeparator)
        .asSequence()
        .filter(String::isNotBlank)
        .flatMap { directory -> executableNames.asSequence().map { Path.of(directory).resolve(it) } }
    if (candidates.none(Files::isExecutable)) {
        throw GradleException("SSH E2E test requires '$tool' on PATH.")
    }
}

internal fun requireWebDav5(output: String) {
    if (!Regex("""\bversion:\s*5\.""").containsMatchIn(output)) {
        throw GradleException("Expected hacdias/webdav v5.x on PATH, but got:\n$output")
    }
}

fun pythonSetupMessage(python: String, requirementsFile: File): String =
    "KDBX E2E tests require the pinned Python dependencies. Create a virtualenv and run:\n" +
        "  $python -m pip install -r ${requirementsFile.absolutePath}\n" +
        "Then select it with -PkdbxE2ePython=/path/to/venv/bin/python."
