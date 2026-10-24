package com.artemchep.keyguard.integration.kdbx

import com.artemchep.keyguard.integration.process.runProcess
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeoutException

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
        val result = try {
            runProcess(command, timeoutSeconds = TIMEOUT_SECONDS) {
                directory(workingDirectory.toFile())
                redirectErrorStream(true)
            }
        } catch (error: IOException) {
            throw AssertionError("Could not start Python command: ${command.joinToString(" ")}", error)
        } catch (error: TimeoutException) {
            throw AssertionError("Python command timed out: ${command.joinToString(" ")}", error)
        }
        val output = result.stdout.trim()
        if (output.isNotEmpty()) {
            println(output)
        }
        if (!result.isSuccess) {
            throw AssertionError(
                "Python command failed with exit code ${result.exitCode}:\n" +
                    command.joinToString(" ") + "\n" + output,
            )
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 120L
    }
}
