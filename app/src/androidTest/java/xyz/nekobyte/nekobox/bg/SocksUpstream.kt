package xyz.nekobyte.nekobox.bg

import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Stands in for the proxy's server: a SOCKS5 server that notes where each client asks to go and answers [body]. */
internal class SocksUpstream(private val body: String) : Closeable {
    val asked = CopyOnWriteArrayList<String>()

    /** What went wrong while serving a client, for a failed test to show. */
    val trouble = CopyOnWriteArrayList<String>()

    // 127.0.0.1 by its bytes: Android's loopback address is the IPv6 one, and the profiles name the IPv4 one.
    private val server = ServerSocket(0, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val port get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use { runCatching { serve(it) }.onFailure { trouble += it.toString() } } }
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 10_000
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        // The greeting: the version and the logins the client offers. None is asked for.
        check(input.readUnsignedByte() == 5)
        input.readFully(ByteArray(input.readUnsignedByte()))
        output.write(byteArrayOf(5, 0))
        // The request: version, connect, a reserved byte, then where to.
        input.readFully(ByteArray(3))
        val host = when (input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
            3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully))
            else -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress
        }
        asked += "$host:${input.readUnsignedShort()}"
        output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        // One HTTP request, read up to its empty line, and one answer.
        var line = ""
        while (true) {
            val next = input.read()
            if (next < 0) return
            if (next == '\n'.code) {
                if (line.isEmpty()) break
                line = ""
            } else if (next != '\r'.code) {
                line += next.toChar()
            }
        }
        output.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
        output.flush()
    }

    override fun close() = server.close()
}
