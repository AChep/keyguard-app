package com.artemchep.keyguard.common.service.sshagent

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SshAgentIpcProtocolTest {
    @Test
    fun `ipc protocol exchanges length prefixed packets`() = runBlocking {
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress("127.0.0.1", 0))

            val serverJob = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    val channel = SshAgentIpcProtocol.open(socket)
                    val packet = requireNotNull(channel.readPacket())
                    channel.writePacket(packet.reversedArray())
                }
            }

            SocketChannel.open().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", server.socket().localPort))
                val channel = SshAgentIpcProtocol.open(client)
                channel.writePacket(byteArrayOf(1, 2, 3, 4))
                val response = requireNotNull(channel.readPacket())
                assertContentEquals(byteArrayOf(4, 3, 2, 1), response)
            }

            serverJob.await()
        }
    }

    @Test
    fun `ipc protocol rejects invalid message length`() = runBlocking {
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress("127.0.0.1", 0))

            val serverJob = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    val channel = SshAgentIpcProtocol.open(socket)
                    val error = assertFailsWith<IllegalArgumentException> {
                        channel.readPacket()
                    }
                    assertEquals("Invalid message length: 0", error.message)
                }
            }

            SocketChannel.open().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", server.socket().localPort))
                withContext(Dispatchers.IO) {
                    val buf = ByteBuffer.allocate(4)
                    buf.putInt(0)
                    buf.flip()
                    while (buf.hasRemaining()) {
                        client.write(buf)
                    }
                }
            }

            serverJob.await()
        }
    }
}
