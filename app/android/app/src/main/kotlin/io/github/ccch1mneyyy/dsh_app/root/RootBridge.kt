package io.github.ccch1mneyyy.dsh_app.root

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.system.Os
import android.system.OsConstants
import io.github.ccch1mneyyy.dsh_app.phone.Json
import io.github.ccch1mneyyy.dsh_app.phone.McpServer
import io.github.ccch1mneyyy.dsh_app.phone.PhoneGuest
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The root bridge: an abstract unix socket the guest relay connects to. The relay runs as
 * **uid 0** inside chroot (root engine) or as the app uid (proot), so both are accepted.
 *
 * Framing is the phone bridge's: a hello line with this launch's token, then MCP over
 * newline-delimited JSON-RPC 2.0. Same relay source, different socket name and server name.
 */
class RootBridge(
    private val server: McpServer,
    private val log: (String) -> Unit,
) {
    @Volatile private var running = false
    private var local: LocalServerSocket? = null
    private var tcp: ServerSocket? = null
    private val open = Collections.synchronizedSet(HashSet<Closeable>())
    private val calls = Executors.newCachedThreadPool { r -> Thread(r, "dsh-root-call").apply { isDaemon = true } }
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val authed = AtomicInteger()

    @Volatile var endpoint: PhoneGuest.Endpoint? = null
        private set

    val listening: Boolean get() = running
    val authenticatedConnections: Int get() = authed.get()

    @Synchronized
    fun start(withTcp: Boolean): PhoneGuest.Endpoint {
        stop()
        val token = hex(32)
        var name: String? = null
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            val candidate = "dsh-app.root." + hex(8)
            try {
                local = LocalServerSocket(candidate)
                name = candidate
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (name == null) log("[dsh-app] root bridge: abstract socket failed (${lastError?.message}); TCP only")
        var port: Int? = null
        if (withTcp || name == null) {
            tcp = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).also { port = it.localPort }
        }
        running = true
        authed.set(0)
        val ep = PhoneGuest.Endpoint(name, port, token)
        endpoint = ep
        local?.let { s -> thread(name = "dsh-root-accept", isDaemon = true) { acceptLocal(s, token) } }
        tcp?.let { s -> thread(name = "dsh-root-accept-tcp", isDaemon = true) { acceptTcp(s, token) } }
        log("[dsh-app] root bridge listening" + (name?.let { " on @$it" } ?: "") + (port?.let { " and 127.0.0.1:$it" } ?: ""))
        return ep
    }

    @Synchronized
    fun stop() {
        if (!running && local == null && tcp == null) return
        running = false
        endpoint = null
        local?.let { s ->
            runCatching { Os.shutdown(s.fileDescriptor, OsConstants.SHUT_RDWR) }
            runCatching { s.close() }
        }
        local = null
        tcp?.let { runCatching { it.close() } }
        tcp = null
        synchronized(open) {
            open.forEach { runCatching { it.close() } }
            open.clear()
        }
    }

    private fun acceptLocal(s: LocalServerSocket, token: String) {
        while (running) {
            val sock: LocalSocket = try {
                s.accept()
            } catch (e: Exception) {
                if (running) log("[dsh-app] root bridge: accept failed: ${e.message}")
                return
            }
            if (!running) {
                runCatching { sock.close() }
                return
            }
            val uid = try {
                sock.peerCredentials.uid
            } catch (_: Exception) {
                -1
            }
            if (!uidOk(uid)) {
                log("[dsh-app] root bridge: refused a connection from uid $uid")
                runCatching { sock.close() }
                continue
            }
            serve(sock, sock.inputStream, sock.outputStream, token) { ms -> runCatching { sock.soTimeout = ms } }
        }
    }

    private fun acceptTcp(s: ServerSocket, token: String) {
        while (running) {
            val sock: Socket = try {
                s.accept()
            } catch (e: Exception) {
                if (running) log("[dsh-app] root bridge: tcp accept failed: ${e.message}")
                return
            }
            if (!running) {
                runCatching { sock.close() }
                return
            }
            serve(sock, sock.getInputStream(), sock.getOutputStream(), token) { ms -> runCatching { sock.soTimeout = ms } }
        }
    }

    /** The relay is either root (0) inside chroot or this app (uid) under proot. Everything else is refused. */
    private fun uidOk(uid: Int): Boolean = uid == 0 || uid == Process.myUid()

    private fun serve(sock: Closeable, raw: InputStream, output: OutputStream, token: String, timeout: (Int) -> Unit) {
        val input = BufferedInputStream(raw)
        if (!slots.tryAcquire()) {
            runCatching { sock.close() }
            return
        }
        open += sock
        thread(name = "dsh-root-conn", isDaemon = true) {
            try {
                timeout(HELLO_TIMEOUT_MS)
                val hello = readLine(input, MAX_HELLO) ?: return@thread
                if (!helloOk(hello, token)) {
                    log("[dsh-app] root bridge: bad token, connection closed")
                    return@thread
                }
                authed.incrementAndGet()
                timeout(0)
                val lock = Object()
                fun send(line: String) {
                    synchronized(lock) {
                        output.write((line + "\n").toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                }
                while (running) {
                    val line = readLine(input, MAX_LINE) ?: break
                    when (val d = server.dispatch(line)) {
                        McpServer.Dispatch.None -> Unit
                        is McpServer.Dispatch.Reply -> send(d.line)
                        is McpServer.Dispatch.Call -> calls.execute {
                            val reply = d.run()
                            runCatching { send(reply) }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                open -= sock
                runCatching { sock.close() }
                slots.release()
            }
        }
    }

    companion object {
        const val MAX_CONNECTIONS = 8
        const val HELLO_TIMEOUT_MS = 10_000
        const val MAX_HELLO = 4096
        const val MAX_LINE = 4 * 1024 * 1024

        private val random = SecureRandom()

        fun hex(bytes: Int): String {
            val b = ByteArray(bytes)
            random.nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        fun helloOk(line: String, token: String): Boolean {
            val m = try {
                Json.parse(line) as? Map<*, *>
            } catch (_: Exception) {
                null
            } ?: return false
            val got = m["token"] as? String ?: return false
            return MessageDigest.isEqual(got.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
        }

        fun readLine(input: InputStream, max: Int): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (b == '\n'.code) return buf.toString("UTF-8").removeSuffix("\r")
                if (buf.size() >= max) throw java.io.IOException("line too long")
                buf.write(b)
            }
        }
    }
}
