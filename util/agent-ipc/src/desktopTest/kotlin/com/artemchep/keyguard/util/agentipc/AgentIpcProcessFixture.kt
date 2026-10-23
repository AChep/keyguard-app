package com.artemchep.keyguard.util.agentipc

import com.artemchep.keyguard.util.ffi.JniLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Paths

/**
 * Separate JVM fixtures. They use the public API without test-framework
 * dependencies, so the child process loads only what production code loads.
 */
object AgentIpcProcessFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args[0]) {
            // Connects like a helper, sends "ping" and prints the reply. Then keeps
            // running until its standard input ends.
            "client" -> {
                val client = connectAgentIpcTestClient(args[1])
                client.write("ping".encodeToByteArray())
                val reply = ByteArray(4)
                var received = 0
                while (received < reply.size) {
                    val count = client.read(reply, received, reply.size - received)
                    if (count < 0) break
                    received += count
                }
                println("REPLY " + reply.decodeToString(endIndex = received))
                client.close()
                System.`in`.readAllBytes()
            }

            // Prints how loading the library ends, then how binding ends.
            "load" -> {
                println(outcome { AgentIpcLibrary.ensureAvailable() })
                println(outcome { AgentIpcListener.bind(agentIpcTestLocation()).close() })
            }

            // Calls every JNI export, on its failure paths too; prints OK.
            "exports" -> {
                exerciseEveryExport()
                println("OK")
            }

            // Runs until its standard input ends.
            "wait" -> System.`in`.readAllBytes()

            else -> error("Unknown fixture mode")
        }
    }

    private fun exerciseEveryExport() = runBlocking(Dispatchers.Default) {
        check(NativeAgentIpc.abiVersion() == AGENT_IPC_ABI_VERSION)
        check(NativeAgentIpc.listen(null, "\uD800", null) == -1L)
        check(NativeAgentIpc.lastError() == "listen: invalid_argument")
        check(NativeAgentIpc.listen(null, "kg/", "") == -1L)
        check(NativeAgentIpc.endpoint(-1) == null)
        check(NativeAgentIpc.endpoint(0) == null)
        check(NativeAgentIpc.accept(-1) == -1L)
        check(NativeAgentIpc.closeListener(0) == -5L)
        check(NativeAgentIpc.peerPid(-1) == -1L)
        check(NativeAgentIpc.read(0, ByteArray(4), 3, 2) == -1L)
        check(NativeAgentIpc.read(0, ByteArray(4), 0, 0) == -1L)
        check(NativeAgentIpc.write(0, ByteArray(4), -1, 1) == -1L)
        check(NativeAgentIpc.write(0, ByteArray(4), 0, 0) == -5L)
        check(NativeAgentIpc.awaitInput(0) == -5L)
        check(NativeAgentIpc.close(-1) == -1L)
        AgentIpcListener.bind(agentIpcTestLocation()).use { listener ->
            connectAgentIpcTestClient(listener.endpoint).use { client ->
                listener.accept().use { connection ->
                    check(connection.peerPid == currentProcessId())
                    client.write(byteArrayOf(1, 2, 3))
                    connection.awaitInputOrDisconnect()
                    check(connection.readExactly(3).contentEquals(byteArrayOf(1, 2, 3)))
                    connection.write(byteArrayOf(9, 4, 5), offset = 1, length = 2)
                    check(client.readExactly(2).contentEquals(byteArrayOf(4, 5)))
                    client.close()
                    check(connection.read(ByteArray(4)) == -1)
                }
            }
        }
    }

    // A missing library must surface as an Exception: callers catch only those.
    @Suppress("TooGenericExceptionCaught")
    private fun outcome(block: () -> Unit): String = try {
        block()
        "OK"
    } catch (error: AgentIpcException) {
        error.kind.name
    } catch (error: Exception) {
        "FAILED ${error::class.simpleName}"
    }

    /** Starts a fixture JVM in [mode]; its standard error goes to its standard output. */
    fun start(
        mode: String,
        vararg args: String,
        properties: List<String> = emptyList(),
    ): Process {
        val classpath = listOf(
            AgentIpcProcessFixture::class.java,
            AgentIpcLibrary::class.java,
            JniLibrary::class.java,
            Unit::class.java,
            kotlinx.coroutines.Job::class.java,
            kotlinx.atomicfu.AtomicBoolean::class.java,
        ).map { Paths.get(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct()
            .joinToString(File.pathSeparator)
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        return ProcessBuilder(
            listOf(java, "-Xcheck:jni") + properties +
                listOf("-cp", classpath, AgentIpcProcessFixture::class.java.name, mode) + args,
        ).redirectErrorStream(true).start()
    }
}
