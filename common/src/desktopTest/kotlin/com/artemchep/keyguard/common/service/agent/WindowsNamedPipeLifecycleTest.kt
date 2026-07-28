package com.artemchep.keyguard.common.service.agent

import com.sun.jna.Platform
import java.io.RandomAccessFile
import java.nio.channels.ClosedChannelException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WindowsNamedPipeLifecycleTest {
    @Test
    fun prepareIsIdempotentAndCloseReleasesThePendingInstance() {
        if (!Platform.isWindows()) return

        val pipeName = "\\\\.\\pipe\\keyguard-ipc-lifecycle-${UUID.randomUUID()}"
        val server = WindowsNamedPipeServer(pipeName)

        server.prepare()
        server.prepare()
        server.close()
        server.close()

        assertFailsWith<ClosedChannelException> {
            server.prepare()
        }
    }

    @Test
    fun preparedInstanceIsImmediatelyConnectableAndAccepted() {
        if (!Platform.isWindows()) return

        val pipeName = "\\\\.\\pipe\\keyguard-ipc-ready-${UUID.randomUUID()}"
        val server = WindowsNamedPipeServer(pipeName)
        val executor = Executors.newFixedThreadPool(2)
        var client: RandomAccessFile? = null
        var connection: WindowsNamedPipeConnection? = null

        try {
            server.prepare()
            val clientFuture = executor.submit<RandomAccessFile> {
                RandomAccessFile(pipeName, "rw")
            }
            val connectionFuture = executor.submit<WindowsNamedPipeConnection> {
                server.accept()
            }

            client = clientFuture.get(5, TimeUnit.SECONDS)
            connection = connectionFuture.get(5, TimeUnit.SECONDS)
        } finally {
            client?.close()
            connection?.close()
            server.close()
            executor.shutdownNow()
        }
    }
}
