package com.artemchep.keyguard.integration.process

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** A controlled child process for the process helper tests. */
object TestChild {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.first()) {
            "exit" -> exitProcess(args[1].toInt())
            "streams" -> {
                println("to stdout")
                System.err.println("to stderr")
            }
            "large" -> {
                val size = args[1].toInt()
                val stdout = ByteArray(size) { 'o'.code.toByte() }
                val stderr = ByteArray(size) { 'e'.code.toByte() }
                System.out.write(stdout)
                System.out.flush()
                System.err.write(stderr)
                System.err.flush()
            }
            "echo" -> System.`in`.copyTo(System.out)
            "hang" -> {
                Files.writeString(Path.of(args[1]), ProcessHandle.current().pid().toString())
                Thread.sleep(Long.MAX_VALUE)
            }
            "serve" -> {
                println("ready")
                System.err.println("warming up")
                Thread.sleep(Long.MAX_VALUE)
            }
            else -> error("Unknown mode: ${args.first()}")
        }
    }

    fun command(vararg args: String): List<String> = listOf(
        ProcessHandle.current().info().command().get(),
        "-cp",
        System.getProperty("java.class.path"),
        TestChild::class.java.name,
    ) + args
}
