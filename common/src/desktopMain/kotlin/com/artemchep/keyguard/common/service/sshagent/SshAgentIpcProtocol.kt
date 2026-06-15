package com.artemchep.keyguard.common.service.sshagent

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

internal object SshAgentIpcProtocol {
    private const val MAX_PACKET_SIZE = 16 * 1024 * 1024

    fun open(
        channel: SocketChannel,
    ): Channel = Channel(channel)

    internal class Channel(
        private val channel: SocketChannel,
    ) : SshAgentPacketChannel {
        override fun readPacket(): ByteArray? {
            val lenBuf = ByteBuffer.allocate(4)
            val bytesRead = readFully(lenBuf)
            if (bytesRead < 4) {
                return null
            }

            lenBuf.flip()
            val len = lenBuf.int
            if (len <= 0 || len > MAX_PACKET_SIZE) {
                throw IllegalArgumentException("Invalid message length: $len")
            }

            val msgBuf = ByteBuffer.allocate(len)
            readFully(msgBuf)
            msgBuf.flip()

            return ByteArray(len).also { packet ->
                msgBuf.get(packet)
            }
        }

        override fun writePacket(
            packet: ByteArray,
        ) {
            require(packet.size in 1..MAX_PACKET_SIZE) {
                "Invalid message length: ${packet.size}"
            }
            val buf = ByteBuffer.allocate(4 + packet.size)
            buf.putInt(packet.size)
            buf.put(packet)
            buf.flip()
            while (buf.hasRemaining()) {
                channel.write(buf)
            }
        }

        private fun readFully(
            buf: ByteBuffer,
        ): Int {
            var totalRead = 0
            while (buf.hasRemaining()) {
                val n = channel.read(buf)
                if (n < 0) {
                    if (totalRead == 0) {
                        return -1
                    }
                    throw EOFException("Unexpected end of stream")
                }
                totalRead += n
            }
            return totalRead
        }
    }
}
