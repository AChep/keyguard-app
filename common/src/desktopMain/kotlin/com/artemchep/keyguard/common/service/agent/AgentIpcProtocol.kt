package com.artemchep.keyguard.common.service.agent

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

internal object AgentIpcProtocol {
    fun open(
        channel: SocketChannel,
        maxPacketSize: Int = AgentIpcFraming.MAX_PACKET_SIZE,
    ): Channel = Channel(channel, maxPacketSize)

    internal class Channel(
        private val channel: SocketChannel,
        private val maxPacketSize: Int,
    ) : AgentPacketChannel {
        override fun readPacket(): ByteArray? {
            val lenBuf = ByteBuffer.allocate(AgentIpcFraming.HEADER_SIZE)
            val bytesRead = readFully(lenBuf)
            if (bytesRead < AgentIpcFraming.HEADER_SIZE) {
                return null
            }

            val len = AgentIpcFraming.decodeHeader(lenBuf.array())
            AgentIpcFraming.requireValidSize(len, maxPacketSize)

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
            val header = AgentIpcFraming.encodeHeader(packet.size, maxPacketSize)
            val buf = ByteBuffer.allocate(header.size + packet.size)
            buf.put(header)
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
