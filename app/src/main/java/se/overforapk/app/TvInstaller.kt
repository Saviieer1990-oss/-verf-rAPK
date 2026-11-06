package se.overforapk.app

import android.content.Context
import dadb.AdbAuthException
import dadb.AdbConnectException
import dadb.AdbException
import dadb.AdbKeyPair
import dadb.Dadb
import dadb.InstallResult
import org.json.JSONArray
import org.json.JSONObject

data class TvDevice(val name: String, val host: String, val port: Int, val wireless: Boolean = false) {
    val key: String get() = "$host:$wireless"
}

/** Resultat av en installation. [model] är TV:ns modellnamn om det gick att läsa. */
data class Outcome(val ok: Boolean, val text: String, val model: String? = null)

/** Kastas när TV:n kräver parkoppling innan en anslutning kan göras. */
class NeedsPairingException(val host: String) : Exception("Parkoppling krävs.")

object TvDevices {
    fun parse(json: String): List<TvDevice> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            TvDevice(it.getString("name"), it.getString("host"), it.optInt("port", 5555), it.optBoolean("wireless", false))
        }
    }.getOrDefault(emptyList())

    fun toJson(list: List<TvDevice>): String = JSONArray().apply {
        list.forEach {
            put(JSONObject().put("name", it.name).put("host", it.host).put("port", it.port).put("wireless", it.wireless))
        }
    }.toString()

    /** Accepterar "192.168.1.20" eller "192.168.1.20:5555". Returnerar null om ogiltigt. */
    fun parseAddress(text: String): Pair<String, Int>? {
        val t = text.trim()
        val host = t.substringBefore(':')
        val port = if (':' in t) t.substringAfter(':').toIntOrNull() else 5555
        val validHost = Regex("[A-Za-z0-9.-]{1,253}").matches(host)
        return if (validHost && port != null && port in 1..65535) host to port else null
    }
}

/** Installerar APK-filer på en Android TV via ADB över nätverket (utan adb-program). */
class TvInstaller(private val library: ApkLibrary, private val context: Context) {
    private companion object {
        val shared = HashMap<String, Dadb>()
        fun sharedKey(d: TvDevice) = "${d.host}:${d.port}"
    }

    // Skapas först vid behov: nyckelgenereringen är tung och behövs inte för port 5555
    private val wireless by lazy { WirelessInstaller(library, context) }

    /**
     * En delad, godkänd anslutning per TV. Varje ny anslutning ger en ny godkännandefråga på TV:n om man inte
     * bockat i "Tillåt alltid", så vi återanvänder samma anslutning till status, start och installation.
     */
    private fun dadbFor(device: TvDevice): Dadb = synchronized(shared) {
        shared.getOrPut(sharedKey(device)) {
            Dadb.create(device.host, device.port, keyPair(), connectTimeout = 8_000, socketTimeout = 0, keepAlive = true)
        }
    }

    private fun dropDadb(device: TvDevice) {
        synchronized(shared) { shared.remove(sharedKey(device)) }?.let { runCatching { it.close() } }
    }

    private fun keyPair(): AdbKeyPair {
        val (priv, pub) = library.adbKeyFiles()
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** Returnerar null vid lyckad parkoppling, annars ett felmeddelande. */
    fun pair(host: String, pairingPort: Int, code: String): String? = try {
        wireless.pair(host, pairingPort, code)
    } catch (e: Throwable) {
        "Trådlös felsökning kunde inte startas (${e.message ?: e.javaClass.simpleName})."
    }

    /** Returns the installed versionCode, 0 when the package is absent, or null when it cannot be checked. */
    fun installedVersion(device: TvDevice, packageName: String): Long? {
        if (!Regex("[A-Za-z0-9_.]+").matches(packageName)) return null
        if (device.wireless) return wireless.installedVersion(device, packageName)
        return try {
            val adb = dadbFor(device)
            val path = adb.shell("pm path $packageName").output.trim()
            if (!path.lineSequence().any { it.startsWith("package:") }) return 0L
            val dump = adb.shell("dumpsys package $packageName").output
            Regex("versionCode=(\\d+)").find(dump)?.groupValues?.get(1)?.toLongOrNull()
        } catch (_: Exception) {
            dropDadb(device)
            null
        }
    }

    /** Startar [packageName] på TV:n. Kastar [NeedsPairingException] om TV:n måste parkopplas först. */
    fun launch(device: TvDevice, packageName: String, appName: String): Outcome {
        if (!Regex("[A-Za-z0-9_.]+").matches(packageName)) return Outcome(false, "Ogiltigt paketnamn.")
        if (device.wireless) {
            return try {
                wireless.launch(device, packageName, appName)
            } catch (e: NeedsPairingException) {
                throw e
            } catch (e: Exception) {
                Outcome(false, "Kunde inte starta appen: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        try {
            val adb = dadbFor(device)
            return if (TvLaunch.start(packageName) { adb.shell(it).output }) Outcome(true, "$appName startades på ${device.name}.")
            else Outcome(false, TvLaunch.failedText())
        } catch (e: AdbAuthException) {
            dropDadb(device)
            return Outcome(false, TvText.DENIED)
        } catch (e: AdbConnectException) {
            dropDadb(device)
            return Outcome(false, "Kunde inte ansluta till ${device.host}. Kontrollera att TV:n är på, att ni är på samma Wi-Fi och att felsökning är påslagen.")
        } catch (e: AdbException) {
            dropDadb(device)
            return Outcome(false, "ADB-fel: ${e.message ?: "okänt fel"}")
        } catch (e: java.io.IOException) {
            dropDadb(device)
            return Outcome(false, "Nätverksfel: ${e.message ?: "anslutningen bröts"}. Om TV:n visade frågan om USB-felsökning: välj Tillåt och försök igen.")
        }
    }

    /**
     * Installerar [entry] på [device]. Kastar [NeedsPairingException] om TV:n måste parkopplas först.
     * Övriga fel returneras som [Outcome] med ok = false. [onStatus] får löpande statustexter.
     */
    fun install(device: TvDevice, entry: ApkEntry, onStatus: (String) -> Unit = {}, cancelled: () -> Boolean = { false }): Outcome {
        if (device.wireless) {
            return try {
                TransferLocks.hold(context).use { wireless.install(device, entry, onStatus, cancelled) }
            } catch (e: NeedsPairingException) {
                throw e
            } catch (e: Exception) {
                Outcome(false, "Installationen misslyckades: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return TransferLocks.hold(context).use {
            InstallEngine.install(
                open = { DadbSession(dadbFor(device)) { dropDadb(device) } },
                parts = library.apkParts(entry),
                appName = entry.displayName,
                deviceName = device.name,
                onStatus = onStatus,
                skipDirect = library.directFailed(device.host),
                onDirectResult = { ok -> library.setDirectFailed(device.host, !ok) },
                cancelled = cancelled,
                tvHost = device.host
            )
        }
    }

    /** Avbryter en pågående installation direkt genom att stänga anslutningen (får blockerade anrop att ge upp). */
    fun abort(device: TvDevice) {
        if (device.wireless) wireless.abort() else dropDadb(device)
    }
}

/** ADB-session över dadb (ADB över nätverk, port 5555). */
class DadbSession(private val adb: Dadb, private val onBroken: () -> Unit = {}) : AdbSession {
    private fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: AdbAuthException) {
        onBroken()
        throw AuthDeniedException(TvText.DENIED)
    } catch (e: AdbConnectException) {
        onBroken()
        throw java.io.IOException("Kunde inte ansluta till TV:n. Kontrollera att TV:n är på, att ni är på samma Wi-Fi och att felsökning är påslagen.")
    } catch (e: AdbException) {
        onBroken()
        throw java.io.IOException("${e.javaClass.simpleName}: ${e.message ?: "ADB-fel"}")
    } catch (e: java.io.IOException) {
        if (e !is RemoteWriteException && e !is AuthDeniedException) onBroken()
        throw e
    }

    override fun shell(command: String): String = guard { adb.shell(command).allOutput }

    override fun push(file: java.io.File, remotePath: String) {
        val result = guard { adb.push(file, remotePath) }
        if (result is dadb.SyncResult.Failure) throw java.io.IOException("Överföringen misslyckades: ${result.reason}")
    }

    override val supportsChunked: Boolean get() = adb.supportsFeature("cmd")

    /**
     * Skriver via shell v2: ger ett tydligt slut på indata, utgångskod och separat felutskrift, så att vi vet
     * att TV:n verkligen tagit emot och skrivit bitarna.
     */
    override fun append(remotePath: String, data: ByteArray, length: Int) = guard {
        adb.openShell("cat >> '$remotePath'").use { stream ->
            var pos = 0
            while (pos < length) {
                val n = minOf(16 * 1024, length - pos)
                stream.write(dadb.ID_STDIN, data.copyOfRange(pos, pos + n))
                pos += n
            }
            stream.write(dadb.ID_CLOSE_STDIN)
            val result = stream.readAll()
            if (result.exitCode != 0 || result.allOutput.isNotBlank()) {
                throw RemoteWriteException(result.allOutput.trim().ifBlank { "kommandot avslutades med kod ${result.exitCode}" })
            }
        }
    }

    override fun streamInstall(parts: List<java.io.File>): String {
        val options = InstallEngine.OPTIONS.split(" ").toTypedArray()
        val result = guard {
            if (parts.size == 1) adb.install(parts[0], *options) else adb.installMultiple(parts, *options)
        }
        return when (result) {
            InstallResult.Success -> "Success"
            is InstallResult.Failure -> result.reason
        }
    }

    /** Anslutningen är delad mellan flera sessioner och stängs inte här. */
    override fun close() {}
}
