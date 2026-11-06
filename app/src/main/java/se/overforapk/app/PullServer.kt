package se.overforapk.app

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal HTTP-server på mobilen. TV:n hämtar filen härifrån (curl/wget på TV:n), så över ADB
 * behöver bara ett kommando utan indata skickas. Stöder HEAD, GET och Range.
 * Bara TV:ns adress (eller samma /24-nät) släpps in, och sökvägen innehåller en slumpad nyckel.
 */
class PullServer(private val files: List<File>, private val tvHost: String) : AutoCloseable {
    private val token = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 8)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "overforapk-pull").apply { isDaemon = true } }
    private val highWater = Array(files.size) { AtomicLong(0) }
    private val activeCount = AtomicInteger(0)
    private val connectionCount = AtomicInteger(0)
    private val allowed: String? = runCatching { InetAddress.getByName(tvHost).hostAddress }.getOrNull()

    @Volatile var lastActivity: Long = System.currentTimeMillis()
        private set
    @Volatile private var closed = false

    val port: Int get() = server.localPort
    val connections: Int get() = connectionCount.get()
    val active: Int get() = activeCount.get()
    val total: Long = files.sumOf { it.length() }

    /** Antal byte som skickats ut (högsta position per fil, så omförsök räknas inte dubbelt). */
    val delivered: Long get() = files.indices.sumOf { minOf(highWater[it].get(), files[it].length()) }

    init {
        pool.execute { acceptLoop() }
    }

    fun url(ip: String, index: Int) = "http://$ip:$port/$token/$index.apk"

    private fun touch() { lastActivity = System.currentTimeMillis() }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            pool.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            if (!sameNetwork(socket.inetAddress)) return
            socket.soTimeout = 30_000
            activeCount.incrementAndGet()
            connectionCount.incrementAndGet()
            touch()
            try {
                respond(socket)
            } catch (_: IOException) {
                // TV:n stängde anslutningen; den kommer tillbaka vid behov
            } finally {
                activeCount.decrementAndGet()
                touch()
            }
        }
    }

    private fun sameNetwork(address: InetAddress): Boolean {
        val want = allowed ?: return true
        val got = address.hostAddress ?: return false
        if (got == want) return true
        val a = want.split('.')
        val b = got.split('.')
        return a.size == 4 && b.size == 4 && a.take(3) == b.take(3)
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString()
        }
        return null
    }

    private fun respond(socket: Socket) {
        val head = readHead(BufferedInputStream(socket.getInputStream())) ?: return
        val lines = head.split("\r\n")
        val first = lines[0].split(" ")
        val method = first.getOrNull(0)
        val path = first.getOrNull(1)?.substringBefore('?') ?: ""
        val out = socket.getOutputStream()

        val index = Regex("/$token/(\\d+)\\.apk").matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()
        if ((method != "GET" && method != "HEAD") || index == null || index !in files.indices) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        val file = files[index]
        val length = file.length()
        var start = 0L
        var end = length - 1
        var partial = false
        lines.drop(1).firstOrNull { it.startsWith("range:", ignoreCase = true) }?.let { header ->
            val r = Regex("bytes=(\\d*)-(\\d*)").find(header) ?: return@let
            val a = r.groupValues[1]
            val b = r.groupValues[2]
            if (a.isNotEmpty()) {
                start = a.toLongOrNull() ?: 0L
                if (b.isNotEmpty()) end = minOf(end, b.toLongOrNull() ?: end)
                partial = true
            } else if (b.isNotEmpty()) {
                start = maxOf(0L, length - (b.toLongOrNull() ?: 0L))
                partial = true
            }
        }
        if (start >= length || end < start) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$length\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        val status = if (partial) "206 Partial Content" else "200 OK"
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: application/vnd.android.package-archive\r\n")
            append("Content-Length: ${end - start + 1}\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (partial) append("Content-Range: bytes $start-$end/$length\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray())
        if (method == "HEAD") { out.flush(); return }

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(64 * 1024)
            var pos = start
            while (pos <= end) {
                val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), end - pos + 1).toInt())
                if (n <= 0) break
                out.write(buffer, 0, n)
                pos += n
                highWater[index].accumulateAndGet(pos) { x, y -> maxOf(x, y) }
                touch()
            }
        }
        out.flush()
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        pool.shutdownNow()
    }

    companion object {
        /** Mobilens egen adress på det nät som når [tvHost] (UDP-"anslutning" skickar inget, den väljer bara väg). */
        fun localAddressFor(tvHost: String): String? {
            val raw = runCatching {
                DatagramSocket().use {
                    it.connect(InetAddress.getByName(tvHost), 9)
                    it.localAddress.hostAddress
                }
            }.getOrNull() ?: return null
            if (raw == "0.0.0.0" || raw == "::") return null
            val clean = raw.substringBefore('%')
            return if (':' in clean) "[$clean]" else clean
        }
    }
}
