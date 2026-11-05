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
     * Some older TV adbd implementations drop the streaming "cmd package install -S"
     * channel while returning the install result. Retry on a fresh ADB connection by
     * transferring to /data/local/tmp first, then invoking the classic package manager.
     */
    private fun installViaPush(device: TvDevice, entry: ApkEntry, parts: List<java.io.File>): Outcome {
        val remoteFiles = parts.mapIndexed { index, file ->
            "/data/local/tmp/overforapk-${System.currentTimeMillis()}-$index.apk"
        }
        return try {
            Dadb.create(device.host, device.port, keyPair(), connectTimeout = 12_000, socketTimeout = 30_000, keepAlive = true).use { adb ->
                parts.zip(remoteFiles).forEach { (file, remote) ->
                    when (val pushed = adb.push(file, remote)) {
                        dadb.SyncResult.Success -> Unit
                        is dadb.SyncResult.Failure -> return Outcome(false, "TV:n avbröt filöverföringen: ${pushed.reason}")
                    }
                }
                val command = if (remoteFiles.size == 1) {
                    "pm install -r \"${remoteFiles.single()}\""
                } else {
                    "pm install-multiple -r " + remoteFiles.joinToString(" ") { "\"$it\"" }
                }
                val response = adb.shell(command)
                val output = response.output.trim()
                if (response.exitCode == 0 && output.startsWith("Success")) {
                    val model = runCatching { adb.shell("getprop ro.product.model").output.trim() }.getOrNull()
                    Outcome(true, "${entry.displayName} är installerad på ${device.name}.", model)
                } else {
                    Outcome(false, "TV:n tog emot APK-filen men Android avvisade installationen: ${output.ifBlank { "inget svar från package manager" }}")
                }
            }
        } catch (e: AdbAuthException) {
            Outcome(false, TvText.DENIED)
        } catch (e: Exception) {
            Outcome(false, "ADB-överföringen bröts även med reservmetoden: ${e.message ?: e.javaClass.simpleName}. Kontrollera att TV:n är på och att den tillåter ADB-felsökning.")
        } finally {
            // Best-effort cleanup. The package manager may already have removed the files.
            runCatching {
                Dadb.create(device.host, device.port, keyPair(), connectTimeout = 8_000, socketTimeout = 8_000).use { adb ->
                    adb.shell("rm -f " + remoteFiles.joinToString(" ") { "\"$it\"" })
                }
            }
        }
    }

    /**
     * Installerar [entry] på [device]. Kastar [NeedsPairingException] om TV:n måste parkopplas först.
     * Övriga fel returneras som [Outcome] med ok = false.
     */
    fun install(device: TvDevice, entry: ApkEntry): Outcome {
        if (device.wireless) {
            return try {
                wireless.install(device, entry)
            } catch (e: NeedsPairingException) {
                throw e
            } catch (e: Exception) {
                Outcome(false, "Installationen misslyckades: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        try {
            Dadb.create(device.host, device.port, keyPair(), connectTimeout = 8_000, socketTimeout = 0).use { adb ->
                val parts = library.apkParts(entry)
                // Kontrollera arkitekturen först, så att inget skickas i onödan och felet blir begripligt
                val apkAbis = AbiCheck.apkAbis(parts)
                val deviceAbis = if (apkAbis.isEmpty()) emptyList() else
                    AbiCheck.parseDeviceAbis(runCatching { adb.shell(AbiCheck.QUERY_COMMAND).output }.getOrDefault(""))
                AbiCheck.mismatch(apkAbis, deviceAbis)?.let { return Outcome(false, it) }
                try {
                    val result = if (parts.size == 1) adb.install(parts[0]) else adb.installMultiple(parts)
                    when (result) {
                        InstallResult.Success -> {
                            val model = runCatching { adb.shell("getprop ro.product.model").output.trim() }.getOrNull()
                            Outcome(true, "${entry.displayName} är installerad på ${device.name}.", model)
                        }
                        is InstallResult.Failure -> Outcome(
                            false,
                            AbiCheck.explainFailure("Installationen misslyckades: ${result.reason.trim()}", apkAbis, deviceAbis)
                        )
                    }
                } catch (e: dadb.AdbConnectionClosedException) {
                    installViaPush(device, entry, parts)
                }
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
}
