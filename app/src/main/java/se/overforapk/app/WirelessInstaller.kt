package se.overforapk.app

import android.content.Context
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.File
import java.io.IOException

/** Parkoppling, anslutning och installation via Trådlös felsökning (ADB över TLS). */
class WirelessInstaller(private val library: ApkLibrary, private val context: Context) {
    private val adb = WirelessAdb.get(context)

    /** Returnerar null vid lyckad parkoppling, annars ett felmeddelande. */
    fun pair(host: String, pairingPort: Int, code: String): String? = try {
        if (adb.pair(host, pairingPort, code.trim())) null
        else "Parkopplingen avvisades. Kontrollera koden. Den ändras varje gång fönstret öppnas på TV:n."
    } catch (e: Exception) {
        "Parkopplingen misslyckades (${e.message ?: e.javaClass.simpleName}). Kontrollera koden och försök igen."
    }

    /** TV:ns anslutningsport ändras när Trådlös felsökning slås på. Slå upp den aktuella automatiskt. */
    private fun currentPort(device: TvDevice): Int =
        NsdWatcher.await(context, SERVICE_TLS_CONNECT, 3_000) { it.host == device.host }?.port ?: device.port

    private fun <T> withConnection(device: TvDevice, block: () -> T): T {
        try {
            runCatching { if (adb.isConnected) adb.disconnect() }
            val port = currentPort(device)
            if (port <= 0) throw IOException("Hittade inte TV:n på nätverket. Kontrollera att Trådlös felsökning är påslagen på TV:n och att ni är på samma Wi-Fi.")
            if (!adb.connect(device.host, port)) throw IOException("Kunde inte ansluta till ${device.host}:$port.")
            return block()
        } catch (e: AdbPairingRequiredException) {
            throw NeedsPairingException(device.host)
        } catch (e: AdbAuthenticationFailedException) {
            throw IOException(TvText.DENIED_WIRELESS)
        } finally {
            runCatching { adb.disconnect() }
        }
    }

    /** Kör en tjänst och, om [input] anges, skickar filen till den. Returnerar all text som kommer tillbaka. */
    private fun run(destination: String, input: File? = null): String {
        adb.openStream(destination).use { stream ->
            if (input != null) {
                val out = stream.openOutputStream()
                input.inputStream().use { it.copyTo(out) }
                out.flush()
            }
            return stream.openInputStream().bufferedReader().readText()
        }
    }

    private fun ok(text: String) = text.lineSequence().any { it.trim().startsWith("Success") }

    fun installedVersion(device: TvDevice, packageName: String): Long? {
        if (!Regex("[A-Za-z0-9_.]+").matches(packageName)) return null
        return try {
            withConnection(device) {
                val path = run("shell:pm path $packageName").trim()
                if (!path.lineSequence().any { it.startsWith("package:") }) 0L
                else {
                    val dump = run("shell:dumpsys package $packageName")
                    Regex("versionCode=(\\d+)").find(dump)?.groupValues?.get(1)?.toLongOrNull()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun launch(device: TvDevice, packageName: String, appName: String): Outcome = try {
        withConnection(device) {
            if (TvLaunch.start(packageName) { run("shell:$it") }) Outcome(true, "$appName startades på ${device.name}.")
            else Outcome(false, TvLaunch.failedText())
        }
    } catch (e: NeedsPairingException) {
        throw e
    } catch (e: IOException) {
        Outcome(false, e.message ?: "Anslutningen misslyckades.")
    }

    fun install(device: TvDevice, entry: ApkEntry): Outcome {
        val parts = library.apkParts(entry)
        return try {
            withConnection(device) {
                // Kontrollera arkitekturen först, så att inget skickas i onödan och felet blir begripligt
                val apkAbis = AbiCheck.apkAbis(parts)
                val deviceAbis = if (apkAbis.isEmpty()) emptyList() else
                    AbiCheck.parseDeviceAbis(runCatching { run("shell:${AbiCheck.QUERY_COMMAND}") }.getOrDefault(""))
                AbiCheck.mismatch(apkAbis, deviceAbis)?.let { return@withConnection Outcome(false, it) }
                val raw = if (parts.size == 1) {
                    val out = run("exec:cmd package install -S ${parts[0].length()}", parts[0])
                    if (ok(out)) Outcome(true, "${entry.displayName} är installerad på ${device.name}.")
                    else Outcome(false, "Installationen misslyckades: ${out.trim()}")
                } else installSplit(entry, parts, device)
                val outcome = if (raw.ok) raw else raw.copy(text = AbiCheck.explainFailure(raw.text, apkAbis, deviceAbis))
                if (outcome.ok) {
                    val model = runCatching { run("shell:getprop ro.product.model").trim() }.getOrNull()
                    outcome.copy(model = model)
                } else outcome
            }
        } catch (e: NeedsPairingException) {
            throw e
        } catch (e: IOException) {
            Outcome(false, e.message ?: "Anslutningen misslyckades.")
        }
    }

    private fun installSplit(entry: ApkEntry, parts: List<File>, device: TvDevice): Outcome {
        val total = parts.sumOf { it.length() }
        val created = run("exec:cmd package install-create -S $total")
        if (!ok(created)) return Outcome(false, "Kunde inte skapa installationssession: ${created.trim()}")
        val id = Regex("""\[(\d+)]""").find(created)?.groupValues?.get(1)
            ?: return Outcome(false, "Kunde inte läsa sessions-id: ${created.trim()}")
        var error: String? = null
        for ((index, file) in parts.withIndex()) {
            val name = "${index}_" + file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val out = run("exec:cmd package install-write -S ${file.length()} $id $name -", file)
            if (!ok(out)) { error = out.trim(); break }
        }
        if (error != null) {
            runCatching { run("exec:cmd package install-abandon $id") }
            return Outcome(false, "Installationen misslyckades: $error")
        }
        val commit = run("exec:cmd package install-commit $id")
        return if (ok(commit)) Outcome(true, "${entry.displayName} är installerad på ${device.name}.")
        else Outcome(false, "Installationen misslyckades: ${commit.trim()}")
    }
}
