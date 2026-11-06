package se.overforapk.app

import android.content.Context
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.File
import java.io.IOException
import kotlin.concurrent.withLock

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

    /**
     * Kopplar upp mot TV:n. Anroparen ansvarar för att hålla [WirelessAdb.lock] (bara en anslutning åt gången,
     * annars kopplar statuskontroll och installation ner varandra) och för att koppla ner.
     */
    private fun connect(device: TvDevice) {
        try {
            runCatching { if (adb.isConnected) adb.disconnect() }
            val port = currentPort(device)
            if (port <= 0) throw IOException("Hittade inte TV:n på nätverket. Kontrollera att Trådlös felsökning är påslagen på TV:n och att ni är på samma Wi-Fi.")
            if (!adb.connect(device.host, port)) throw IOException("Kunde inte ansluta till ${device.host}:$port.")
        } catch (e: AdbPairingRequiredException) {
            throw NeedsPairingException(device.host)
        } catch (e: AdbAuthenticationFailedException) {
            throw AuthDeniedException(TvText.DENIED_WIRELESS)
        }
    }

    private fun <T> withConnection(device: TvDevice, block: () -> T): T = adb.lock.withLock {
        try {
            connect(device)
            block()
        } finally {
            runCatching { adb.disconnect() }
        }
    }

    /** En session som håller anslutningen och låset tills den stängs. */
    private inner class Session(device: TvDevice) : AdbSession {
        init {
            adb.lock.lock()
            try {
                connect(device)
            } catch (e: Throwable) {
                runCatching { adb.disconnect() }
                adb.lock.unlock()
                throw e
            }
        }

        override fun shell(command: String): String = run("exec:$command")

        override fun push(file: File, remotePath: String) {
            // head -c läser exakt så många byte som filen har, så ingen EOF-signal behövs
            run("exec:head -c ${file.length()} > $remotePath", file)
        }

        override val supportsChunked: Boolean get() = true

        override fun append(remotePath: String, data: ByteArray, length: Int) {
            val text = adb.openStream("exec:head -c $length >> '$remotePath'").use { stream ->
                val out = stream.openOutputStream()
                out.write(data, 0, length)
                out.flush()
                stream.openInputStream().bufferedReader().readText()
            }
            if (text.isNotBlank()) throw RemoteWriteException(text.trim())
        }

        override fun streamInstall(parts: List<File>): String {
            val options = InstallEngine.OPTIONS
            if (parts.size == 1) return run("exec:cmd package install -S ${parts[0].length()} $options", parts[0])
            val created = run("exec:cmd package install-create -S ${parts.sumOf { it.length() }} $options")
            if (!ok(created)) return created
            val id = Regex("""\[(\d+)]""").find(created)?.groupValues?.get(1) ?: return created
            for ((index, file) in parts.withIndex()) {
                val name = "${index}_" + file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val out = run("exec:cmd package install-write -S ${file.length()} $id $name -", file)
                if (!ok(out)) {
                    runCatching { run("exec:cmd package install-abandon $id") }
                    return out
                }
            }
            return run("exec:cmd package install-commit $id")
        }

        override fun close() {
            runCatching { adb.disconnect() }
            adb.lock.unlock()
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

    fun install(device: TvDevice, entry: ApkEntry, onStatus: (String) -> Unit = {}, cancelled: () -> Boolean = { false }): Outcome =
        InstallEngine.install(
            open = { Session(device) },
            parts = library.apkParts(entry),
            appName = entry.displayName,
            deviceName = device.name,
            onStatus = onStatus,
            skipDirect = library.directFailed(device.host),
            onDirectResult = { ok -> library.setDirectFailed(device.host, !ok) },
            cancelled = cancelled,
            tvHost = device.host
        )

    /** Kopplar ner direkt (utan att vänta på låset) så att pågående överföring avbryts. */
    fun abort() {
        runCatching { adb.disconnect() }
    }
}
