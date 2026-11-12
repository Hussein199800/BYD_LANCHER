package app.bydlauncher.probe

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal read-only HTTP server: every request returns the report file as plain text.
 * It exists only so the report can be fetched from a phone on the same network while
 * the app is open; it serves nothing else and accepts no input.
 */
class ReportServer(private val port: Int, private val file: () -> File) {

    @Volatile private var socket: ServerSocket? = null

    fun start() {
        thread(name = "report-server", isDaemon = true) {
            try {
                val server = ServerSocket(port)
                socket = server
                while (!server.isClosed) {
                    val client = server.accept()
                    thread(isDaemon = true) { serve(client) }
                }
            } catch (_: Throwable) {
                // Port busy or server stopped: the on-device file is still available.
            }
        }
    }

    fun stop() {
        try { socket?.close() } catch (_: Throwable) {}
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                it.soTimeout = 5000
                // Read and discard the request line; the response is always the same.
                it.getInputStream().bufferedReader().readLine()
                val body = try { file().readBytes() } catch (_: Throwable) { "report not ready".toByteArray() }
                val head = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n"
                val out = it.getOutputStream()
                out.write(head.toByteArray())
                out.write(body)
                out.flush()
            }
        } catch (_: Throwable) {
        }
    }
}
