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
    private val wireless = WirelessInstaller(library, context)

    private fun keyPair(): AdbKeyPair {
        val (priv, pub) = library.adbKeyFiles()
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** Returnerar null vid lyckad parkoppling, annars ett felmeddelande. */
    fun pair(host: String, pairingPort: Int, code: String): String? = wireless.pair(host, pairingPort, code)

    /**
     * Installerar [entry] på [device]. Kastar [NeedsPairingException] om TV:n måste parkopplas först.
     * Övriga fel returneras som [Outcome] med ok = false.
     */
    fun install(device: TvDevice, entry: ApkEntry): Outcome {
        if (device.wireless) return wireless.install(device, entry)
        try {
            Dadb.create(device.host, device.port, keyPair(), connectTimeout = 8_000, socketTimeout = 0).use { adb ->
                val parts = library.apkParts(entry)
                val result = if (parts.size == 1) adb.install(parts[0]) else adb.installMultiple(parts)
                return when (result) {
                    InstallResult.Success -> {
                        val model = runCatching { adb.shell("getprop ro.product.model").output.trim() }.getOrNull()
                        Outcome(true, "${entry.displayName} är installerad på ${device.name}.", model)
                    }
                    is InstallResult.Failure -> Outcome(false, "Installationen misslyckades: ${result.reason.trim()}")
                }
            }
        } catch (e: AdbAuthException) {
            return Outcome(false, "TV:n godkände inte anslutningen. Titta på TV:ns skärm, välj Tillåt (och Tillåt alltid) och försök igen.")
        } catch (e: AdbConnectException) {
            return Outcome(false, "Kunde inte ansluta till ${device.host}. Kontrollera att TV:n är på, att ni är på samma Wi-Fi och att felsökning är påslagen.")
        } catch (e: AdbException) {
            return Outcome(false, "ADB-fel: ${e.message ?: "okänt fel"}")
        }
    }
}
