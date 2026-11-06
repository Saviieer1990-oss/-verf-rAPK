package se.overforapk.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

data class AdbService(val name: String, val host: String, val port: Int)

const val SERVICE_TLS_CONNECT = "_adb-tls-connect._tcp"
const val SERVICE_TLS_PAIRING = "_adb-tls-pairing._tcp"

/** Lyssnar efter ADB-tjänster som enheter annonserar på nätverket (mDNS). Ger värd och aktuell port. */
class NsdWatcher(
    context: Context,
    private val type: String,
    private val onChange: (List<AdbService>) -> Unit = {}
) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val found = LinkedHashMap<String, AdbService>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var active = false
    private val own = localAddresses()

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            synchronized(this@NsdWatcher) { queue.addLast(serviceInfo) }
            resolveNext()
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val snapshot = synchronized(this@NsdWatcher) {
                found.remove(serviceInfo.serviceName)
                found.values.toList()
            }
            onChange(snapshot)
        }
    }

    fun start() {
        synchronized(this) { if (active) return; active = true }
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery) }
    }

    fun stop() {
        synchronized(this) { if (!active) return; active = false; queue.clear() }
        runCatching { nsd.stopServiceDiscovery(discovery) }
    }

    fun snapshot(): List<AdbService> = synchronized(this) { found.values.toList() }

    private fun resolveNext() {
        val next = synchronized(this) {
            if (resolving || !active) return
            val n = queue.removeFirstOrNull() ?: return
            resolving = true
            n
        }
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = finishResolve(null)
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) = finishResolve(toService(serviceInfo))
        })
    }

    private fun finishResolve(service: AdbService?) {
        val snapshot = synchronized(this) {
            resolving = false
            if (service != null && service.host !in own) found[service.name] = service
            found.values.toList()
        }
        if (service != null) onChange(snapshot)
        resolveNext()
    }

    private fun toService(info: NsdServiceInfo): AdbService? {
        val address = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address }
        } else {
            @Suppress("DEPRECATION")
            info.host?.takeIf { it is Inet4Address }
        }
        val host = address?.hostAddress ?: return null
        return AdbService(info.serviceName, host, info.port)
    }

    companion object {
        fun localAddresses(): Set<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .mapNotNull { it.hostAddress }
                .toSet()
        }.getOrDefault(emptySet())

        /** Blockerande: väntar tills en tjänst som uppfyller [match] syns, eller tills tiden gått ut. */
        fun await(context: Context, type: String, timeoutMs: Long, match: (AdbService) -> Boolean): AdbService? {
            val watcher = NsdWatcher(context, type)
            watcher.start()
            try {
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    watcher.snapshot().firstOrNull(match)?.let { return it }
                    Thread.sleep(150)
                }
            } finally {
                watcher.stop()
            }
            return null
        }

        /** Blockerande: samlar alla tjänster som syns under [durationMs]. */
        fun collect(context: Context, type: String, durationMs: Long): List<AdbService> {
            val watcher = NsdWatcher(context, type)
            watcher.start()
            try { Thread.sleep(durationMs) } finally { watcher.stop() }
            return watcher.snapshot()
        }
    }
}

/** En enhet som hittats på nätverket men ännu inte sparats. */
data class FoundDevice(val host: String, val port: Int, val wireless: Boolean)

object TvFinder {
    /** Kontrollerar om [port] är öppen på [host]. */
    fun portOpen(host: String, port: Int, timeoutMs: Int = 600): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), timeoutMs); true }
    }.getOrDefault(false)

    /** Letar både efter Trådlös felsökning (mDNS) och öppen port 5555. Enheter med port 5555 går före (kräver ingen parkoppling). */
    suspend fun scan(context: Context, onProgress: (Int) -> Unit = {}): List<FoundDevice>? {
        val legacy = NetworkScanner.findOpenPort(5555, 500) { done, total -> onProgress(done * 100 / total) }
            ?: return null
        val mdns = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            NsdWatcher.collect(context, SERVICE_TLS_CONNECT, 3_000)
        }
        val result = LinkedHashMap<String, FoundDevice>()
        mdns.forEach { result[it.host] = FoundDevice(it.host, it.port, wireless = true) }
        legacy.forEach { result[it] = FoundDevice(it, 5555, wireless = false) }
        return result.values.toList()
    }
}
