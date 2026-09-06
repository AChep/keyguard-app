package com.artemchep.keyguard.integration.kdbx

import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class PythonKdbxOracle(
    private val executable: String,
    private val driver: Path,
    private val workingDirectory: Path,
) {
    fun generate(
        seed: Path,
        database: Path,
        manifest: Path,
        password: String?,
        keyfile: Path?,
    ) {
        run(
            buildList {
                add("generate")
                add("--seed")
                add(seed.toString())
                add("--database")
                add(database.toString())
                add("--manifest")
                add(manifest.toString())
                if (password != null) {
                    add("--password")
                    add(password)
                }
                if (keyfile != null) {
                    add("--keyfile")
                    add(keyfile.toString())
                }
            },
        )
    }

    fun verify(
        database: Path,
        manifest: Path,
        password: String?,
        keyfile: Path?,
    ) {
        run(
            buildList {
                add("verify")
                add("--database")
                add(database.toString())
                add("--manifest")
                add(manifest.toString())
                if (password != null) {
                    add("--password")
                    add(password)
                }
                if (keyfile != null) {
                    add("--keyfile")
                    add(keyfile.toString())
                }
            },
        )
    }

    private fun run(arguments: List<String>) {
        val command = listOf(executable, driver.toString()) + arguments
        val process = try {
            ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            throw AssertionError("Could not start Python command: ${command.joinToString(" ")}", error)
        }
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw AssertionError("Python command timed out: ${command.joinToString(" ")}")
        }
        val output = process.inputStream.readBytes().decodeToString().trim()
        if (output.isNotEmpty()) {
            println(output)
        }
        if (process.exitValue() != 0) {
            throw AssertionError(
                "Python command failed with exit code ${process.exitValue()}:\n" +
                    command.joinToString(" ") + "\n" + output,
            )
        }
    }
}
