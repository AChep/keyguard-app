package com.artemchep.keyguard.common.service.agent

import com.sun.jna.Platform
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentIpcPeerVerificationTest {
    @Test
    fun unixSocketCredentialsIdentifyConnectingProcessAndUser() {
        if (Platform.isWindows()) return

        val directory = Files.createTempDirectory("keyguard-ipc-peer-test")
        val socketPath = directory.resolve("ipc.sock")

        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(socketPath))
                SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                    client.connect(UnixDomainSocketAddress.of(socketPath))
                    server.accept().use { accepted ->
                        val credentials = readUnixAgentPeerCredentials(accepted)
                        val currentUid = Files.getAttribute(
                            directory,
                            "unix:uid",
                        ) as Number

                        assertEquals(ProcessHandle.current().pid(), credentials.pid)
                        assertEquals(currentUid.toLong(), credentials.uid)
                    }
                }
            }
        } finally {
            Files.deleteIfExists(socketPath)
            Files.deleteIfExists(directory)
        }
    }
}
