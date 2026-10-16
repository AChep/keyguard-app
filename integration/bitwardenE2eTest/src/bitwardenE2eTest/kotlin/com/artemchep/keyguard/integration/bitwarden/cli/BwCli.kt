package com.artemchep.keyguard.integration.bitwarden.cli

import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.integration.bitwarden.VaultwardenApi
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * `bw`: creates and seeds accounts on a running Bitwarden-compatible server, with Keyguard's
 * own crypto. Run it with `./gradlew -q :integration:bitwardenE2eTest:bw --args="..."`.
 */
fun main(args: Array<String>) {
    val out = System.out
    // The last stdout line must be the JSON object, so whatever the libraries print goes to stderr.
    System.setOut(System.err)
    exitProcess(BwCli.run(args.toList(), out = out, err = System.err))
}

internal object BwCli {
    const val EXIT_OK = 0
    const val EXIT_FAILURE = 1
    const val EXIT_USAGE = 2

    fun run(args: List<String>, out: PrintStream, err: PrintStream): Int = when (val command = BwArgs.parse(args)) {
        BwCommand.Help -> {
            out.println(BwArgs.USAGE)
            EXIT_OK
        }

        is BwCommand.Invalid -> {
            err.println("bw: ${command.message}")
            err.println()
            err.println(BwArgs.USAGE)
            EXIT_USAGE
        }

        is BwCommand.Run -> execute(command, out, err)
    }

    // Any failure ends the command with its message; the harness puts the HTTP status and body in it.
    @Suppress("TooGenericExceptionCaught")
    private fun execute(command: BwCommand.Run, out: PrintStream, err: PrintStream): Int = try {
        val result = when (command) {
            is BwCommand.Code -> code(command)
            is BwCommand.Remote -> VaultwardenApi(command.account.server).use { api ->
                runBlocking { api.execute(command) }
            }
        }
        result.print(out, jsonOnly = command.json)
        EXIT_OK
    } catch (e: Exception) {
        err.println("bw: ${describe(e)}")
        EXIT_FAILURE
    }

    private fun describe(e: Exception): String = when {
        e is ApiException && e.type is ApiException.Type.TwoFaRequired ->
            "HTTP ${e.statusCode.value}: the account has two-step login on; bw can not log in to it."

        e is HttpException -> "HTTP ${e.statusCode.value}: ${e.message}"

        // The harness fails with these, its message carries the request and the response.
        e is IllegalStateException || e is IllegalArgumentException -> e.message ?: e.toString()

        else -> e.toString()
    }
}
