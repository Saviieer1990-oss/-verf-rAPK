package se.overforapk.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Letar efter enheter på det lokala nätverket som har ADB-porten öppen. Skickar inget till enheterna. */
object NetworkScanner {

    private data class Subnet(val own: Int, val base: Int)

    private fun ipToString(ip: Int) =
        "${ip ushr 24 and 255}.${ip ushr 16 and 255}.${ip ushr 8 and 255}.${ip and 255}"

    private fun localSubnet(): Subnet? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return null
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
            for (addr in nif.interfaceAddresses) {
                val inet = addr.address as? Inet4Address ?: continue
                if (!inet.isSiteLocalAddress) continue
                val b = inet.address
                val ip = (b[0].toInt() and 255 shl 24) or (b[1].toInt() and 255 shl 16) or
                    (b[2].toInt() and 255 shl 8) or (b[3].toInt() and 255)
                // Skannar alltid högst ett /24-nät (254 adresser)
                return Subnet(ip, ip and 0xFFFFFF00.toInt())
            }
        }
        return null
    }

    /** Returnerar IP-adresser med öppen port, eller null om inget lokalt nätverk hittades. */
    suspend fun findOpenPort(
        port: Int = 5555,
        timeoutMs: Int = 500,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<String>? = withContext(Dispatchers.IO) {
        val subnet = localSubnet() ?: return@withContext null
        val candidates = (1..254).map { subnet.base or it }.filter { it != subnet.own }
        val done = AtomicInteger(0)
        val gate = Semaphore(48)
        coroutineScope {
            candidates.map { ip ->
                async {
                    gate.withPermit {
                        val host = ipToString(ip)
                        val open = runCatching {
                            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
                        }.getOrDefault(false)
                        onProgress(done.incrementAndGet(), candidates.size)
                        if (open) host else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}
