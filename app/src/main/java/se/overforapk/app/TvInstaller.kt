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
    // Skapas först vid behov: nyckelgenereringen är tung och behövs inte för port 5555
    private val wireless by lazy { WirelessInstaller(library, context) }

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
            Dadb.create(device.host, device.port, keyPair(), connectTimeout = 8_000, socketTimeout = 0).use { adb ->
                val path = adb.shell("pm path $packageName").output.trim()
                if (!path.lineSequence().any { it.startsWith("package:") }) return 0L
                val dump = adb.shell("dumpsys package $packageName").output
                Regex("versionCode=(\\d+)").find(dump)?.groupValues?.get(1)?.toLongOrNull()
            }
        } catch (_: Exception) {
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
            Dadb.create(device.host, device.port, keyPair(), connectTimeout = 8_000, socketTimeout = 0).use { adb ->
                return if (TvLaunch.start(packageName) { adb.shell(it).output }) Outcome(true, "$appName startades på ${device.name}.")
                else Outcome(false, TvLaunch.failedText())
            }
        } catch (e: AdbAuthException) {
            return Outcome(false, TvText.DENIED)
        } catch (e: AdbConnectException) {
            return Outcome(false, "Kunde inte ansluta till ${device.host}. Kontrollera att TV:n är på, att ni är på samma Wi-Fi och att felsökning är påslagen.")
        } catch (e: AdbException) {
            return Outcome(false, "ADB-fel: ${e.message ?: "okänt fel"}")
        } catch (e: java.io.IOException) {
            return Outcome(false, "Nätverksfel: ${e.message ?: "anslutningen bröts"}. Om TV:n visade frågan om USB-felsökning: välj Tillåt och försök igen.")
        }
    }

    /**
     * Installerar [entry] på [device]. Kastar [NeedsPairingException] om TV:n måste parkopplas först.
     * Övriga fel returneras som [Outcome] med ok = false. [onStatus] får löpande statustexter.
     */
    fun install(device: TvDevice, entry: ApkEntry, onStatus: (String) -> Unit = {}): Outcome {
        if (device.wireless) {
            return try {
                TransferLocks.hold(context).use { wireless.install(device, entry, onStatus) }
            } catch (e: NeedsPairingException) {
                throw e
            } catch (e: Exception) {
                Outcome(false, "Installationen misslyckades: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return TransferLocks.hold(context).use {
            InstallEngine.install(
                open = { DadbSession(Dadb.create(device.host, device.port, keyPair(), connectTimeout = 8_000, socketTimeout = 0, keepAlive = true)) },
                parts = library.apkParts(entry),
                appName = entry.displayName,
                deviceName = device.name,
                onStatus = onStatus,
                skipDirect = library.directFailed(device.host),
                onDirectResult = { ok -> library.setDirectFailed(device.host, !ok) }
            )
        }
    }
}

/** ADB-session över dadb (ADB över nätverk, port 5555). */
class DadbSession(private val adb: Dadb) : AdbSession {
    private fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: AdbAuthException) {
        throw AuthDeniedException(TvText.DENIED)
    } catch (e: AdbConnectException) {
        throw java.io.IOException("Kunde inte ansluta till TV:n. Kontrollera att TV:n är på, att ni är på samma Wi-Fi och att felsökning är påslagen.")
    } catch (e: AdbException) {
        throw java.io.IOException(e.message ?: "ADB-fel")
    }

    override fun shell(command: String): String = guard { adb.shell(command).allOutput }

    override fun push(file: java.io.File, remotePath: String) {
        val result = guard { adb.push(file, remotePath) }
        if (result is dadb.SyncResult.Failure) throw java.io.IOException("Överföringen misslyckades: ${result.reason}")
    }

    override val supportsChunked: Boolean get() = adb.supportsFeature("cmd")

    override fun append(remotePath: String, data: ByteArray, length: Int) = guard {
        adb.open("exec:head -c $length >> $remotePath").use { stream ->
            stream.sink.write(data, 0, length)
            stream.sink.flush()
            stream.source.readString(Charsets.UTF_8) // väntar tills kommandot är klart
            Unit
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

    override fun close() = adb.close()
}
