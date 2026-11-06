package se.overforapk.app

import java.io.File
import java.io.IOException

/** En uppkopplad ADB-session mot en TV. Implementeras av både dadb (port 5555) och libadb (Trådlös felsökning). */
interface AdbSession : AutoCloseable {
    /** Kör ett kommando på TV:n och returnerar utdata. */
    fun shell(command: String): String

    /** Skickar [file] till [remotePath] på TV:n i ett svep. Kastar vid fel. */
    fun push(file: File, remotePath: String)

    /** Kan filen skickas i små bitar med [append]? (Kräver exec-tjänsten på TV:n.) */
    val supportsChunked: Boolean

    /** Lägger [length] byte ur [data] sist i filen [remotePath] på TV:n. Kastar [RemoteWriteException] om TV:n inte kan skriva. */
    fun append(remotePath: String, data: ByteArray, length: Int)

    /** Direkt installation via en ström (cmd package install -S). Returnerar TV:ns svar som text. */
    fun streamInstall(parts: List<File>): String
}

/** TV:n tog emot kommandot men kunde inte skriva datan (fullt, ingen skrivrätt, kommandot saknas). Hjälper inte att försöka igen. */
class RemoteWriteException(message: String) : IOException(message)

/** TV:n nekade anslutningen (användaren valde Ej tillåt eller svarade inte). Ska inte försökas igen automatiskt. */
class AuthDeniedException(message: String) : IOException(message)

/**
 * Installerar en app på TV:n så stabilt som möjligt:
 *  1. Direkt ström, med en ny anslutning om den bryts.
 *  2. Reservmetod: skicka filen till TV:ns lagring, kontrollera storleken och kör pm install -r därifrån.
 * Befintlig appdata behålls (-r). Tydliga fel när TV:n avvisar installationen.
 */
object InstallEngine {
    /** -r ersätter en installerad version och behåller dess data, -t tillåter test-APK:er. */
    const val OPTIONS = "-r -t"

    private val rejection = Regex("INSTALL_[A-Z_]+|Failure")

    private fun isSuccess(text: String) = text.lineSequence().any { it.trim().startsWith("Success") }
    private fun isRejection(text: String) = rejection.containsMatchIn(text)

    private fun success(session: AdbSession, appName: String, deviceName: String): Outcome {
        val model = runCatching { session.shell("getprop ro.product.model").trim() }.getOrNull()?.takeIf { it.isNotBlank() }
        return Outcome(true, "$appName är installerad på $deviceName.", model)
    }

    /** Skalar väntetiderna mellan försök (sätts till 0 i tester). */
    internal var pauseFactor = 1.0

    private fun pause(ms: Long) = try { Thread.sleep((ms * pauseFactor).toLong()) } catch (_: InterruptedException) {}

    /** Ge upp efter så här många återanslutningar i rad utan att något nytt kommit fram, eller totalt. */
    private const val MAX_STALLS = 5
    private const val MAX_RECONNECTS = 150

    fun install(
        open: () -> AdbSession,
        parts: List<File>,
        appName: String,
        deviceName: String,
        onStatus: (String) -> Unit = {},
        skipDirect: Boolean = false,
        onDirectResult: (Boolean) -> Unit = {}
    ): Outcome {
        val apkAbis = AbiCheck.apkAbis(parts)
        var deviceAbis: List<String> = emptyList()
        var lastError = ""

        // Steg 1: direkt ström (en gång). Hoppas över på TV:ar där den bevisligen brukar brytas.
        if (!skipDirect) {
            try {
                open().use { s ->
                    if (apkAbis.isNotEmpty()) {
                        deviceAbis = AbiCheck.parseDeviceAbis(runCatching { s.shell(AbiCheck.QUERY_COMMAND) }.getOrDefault(""))
                    }
                    AbiCheck.mismatch(apkAbis, deviceAbis)?.let { return Outcome(false, it) }
                    onStatus("Installerar $appName på $deviceName…")
                    val response = s.streamInstall(parts)
                    if (isSuccess(response)) { onDirectResult(true); return success(s, appName, deviceName) }
                    // TV:n har läst filen och sagt nej: förklara varför, försök inte igen.
                    if (isRejection(response)) return Outcome(false, InstallErrors.explain(response, apkAbis, deviceAbis))
                    lastError = response.trim().ifBlank { "tomt svar från TV:n" }
                }
            } catch (e: NeedsPairingException) {
                throw e
            } catch (e: AuthDeniedException) {
                return Outcome(false, e.message ?: TvText.DENIED)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
            onDirectResult(false)
            onStatus("Direkt överföring bröts. Skickar filen i små bitar som kan återupptas…")
            pause(1_000)
        }

        return chunkedInstall(open, parts, apkAbis, deviceAbis, appName, deviceName, onStatus, lastError)
    }

    /**
     * Skickar filen till TV:ns lagring i små bitar. Bryts anslutningen återansluter vi, läser hur mycket som kom fram
     * och fortsätter därifrån (det som kommit fram är alltid en korrekt början på filen). Därefter pm install -r.
     */
    private fun chunkedInstall(
        open: () -> AdbSession,
        parts: List<File>,
        apkAbis: Set<String>,
        initialDeviceAbis: List<String>,
        appName: String,
        deviceName: String,
        onStatus: (String) -> Unit,
        previousError: String
    ): Outcome {
        var deviceAbis = initialDeviceAbis
        val total = parts.sumOf { it.length() }
        var remotes: List<String> = emptyList()
        var directory: String? = null
        var appendedSomething = false
        var stalls = 0
        var session: AdbSession? = null
        var reconnects = 0
        var chunk = 64 * 1024
        var lastError = previousError
        var bestSent = 0L

        fun current(): AdbSession = session ?: open().also { session = it }
        fun drop() { runCatching { session?.close() }; session = null }

        /** Returnerar null om vi ska försöka igen, annars slutresultatet. */
        fun onFailure(e: Exception): Outcome? {
            if (e is NeedsPairingException) throw e
            if (e is RemoteWriteException) return Outcome(false, remoteWriteMessage(e))
            if (e is AuthDeniedException) return Outcome(false, e.message ?: TvText.DENIED)
            lastError = e.message ?: e.javaClass.simpleName
            drop()
            reconnects++
            if (stalls >= MAX_STALLS || reconnects > MAX_RECONNECTS) {
                return Outcome(
                    false,
                    "Överföringen till TV:n bröts om och om igen. Som mest kom ${formatBytes(bestSent)} av ${formatBytes(total)} fram. " +
                        "Wi-Fi-signalen eller TV:ns nätverk är sannolikt instabilt. Prova med TV:n på kabel, flytta närmare routern " +
                        "eller starta om TV:n och routern och försök igen.\n\nTekniskt: $lastError"
                )
            }
            onStatus("Anslutningen bröts (${formatBytes(bestSent)} av ${formatBytes(total)} kom fram). Återansluter och fortsätter där det slutade…")
            pause(1_500)
            return null
        }

        try {
            // Välj en plats på TV:n där filer faktiskt kan sparas och mätas
            while (directory == null) {
                try {
                    onStatus("Kontrollerar att TV:n kan ta emot filen…")
                    val s = current()
                    val errors = ArrayList<String>()
                    val found = probeDirectory(s, errors) ?: return Outcome(
                        false,
                        "TV:n tillåter inte att filer sparas via ADB, så överföringen kan inte genomföras.\n\nTekniskt:\n" + errors.joinToString("\n")
                    )
                    directory = found
                    // Samma filnamn vid varje försök med samma fil, så att en avbruten överföring kan återupptas vid nästa försök
                    remotes = parts.map { "$found/overforapk-${(it.name + it.length() + it.lastModified()).hashCode().toUInt()}.apk" }
                } catch (e: Exception) {
                    onFailure(e)?.let { return it }
                }
            }

            var sentBefore = 0L
            for ((i, file) in parts.withIndex()) {
                val remote = remotes[i]
                appendedSomething = false
                var lastOffset = -1L
                while (true) {
                    try {
                        val s = current()
                        if (deviceAbis.isEmpty() && apkAbis.isNotEmpty() && i == 0) {
                            deviceAbis = AbiCheck.parseDeviceAbis(runCatching { s.shell(AbiCheck.QUERY_COMMAND) }.getOrDefault(""))
                            AbiCheck.mismatch(apkAbis, deviceAbis)?.let { return Outcome(false, it) }
                        }
                        var offset = remoteSize(s, remote)
                        if (offset > file.length()) { s.shell("rm -f '$remote'"); offset = 0 }
                        if (offset == file.length()) break
                        if (appendedSomething && offset == 0L) {
                            // TV:n sa ok till varje bit men filen är tom: skicka inte om samma sak i all evighet
                            return Outcome(
                                false,
                                "TV:n tog emot data men filen på TV:n är tom. TV:ns lagring eller filkommandon fungerar inte som appen förväntar sig.\n\nTekniskt:\n" + diagnose(s, remote)
                            )
                        }
                        if (offset > lastOffset) {
                            stalls = 0
                        } else {
                            // Inget nytt kom fram sedan förra försöket: skicka mindre bitar och räkna upp
                            stalls++
                            chunk = maxOf(8 * 1024, chunk / 2)
                            if (stalls >= MAX_STALLS) throw IOException("Överföringen kommer inte framåt")
                        }
                        lastOffset = offset
                        bestSent = maxOf(bestSent, sentBefore + offset)
                        if (!s.supportsChunked) {
                            onStatus("Skickar filen till TV:n…")
                            s.push(file, remote)
                            continue
                        }
                        var lastPercent = -1
                        sendFrom(s, file, remote, offset, chunk) { pos ->
                            appendedSomething = true
                            bestSent = maxOf(bestSent, sentBefore + pos)
                            val percent = if (total == 0L) 100 else ((sentBefore + pos) * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onStatus("Skickar till TV:n… $percent % (${formatBytes(sentBefore + pos)} av ${formatBytes(total)})")
                            }
                        }
                    } catch (e: Exception) {
                        onFailure(e)?.let { return it }
                    }
                }
                sentBefore += file.length()
            }

            val command = if (parts.size == 1) "pm install ${InstallEngine.OPTIONS} '${remotes[0]}'"
            else "pm install-multiple ${InstallEngine.OPTIONS} ${remotes.joinToString(" ") { "'$it'" }}"
            var response = ""
            var done = false
            for (attempt in 1..3) {
                try {
                    val s = current()
                    onStatus("Filen är framme. Installerar från TV:ns lagring…")
                    s.shell("chmod 644 ${remotes.joinToString(" ") { "'$it'" }}")
                    response = s.shell(command)
                    done = true
                    break
                } catch (e: Exception) {
                    onFailure(e)?.let { return it }
                }
            }
            if (!done) {
                return Outcome(false, "Filen kom fram men TV:n svarade inte på installationen.\n\nTekniskt: $lastError")
            }
            return if (isSuccess(response)) success(current(), appName, deviceName)
            else Outcome(false, InstallErrors.explain(response, apkAbis, deviceAbis))
        } finally {
            if (session != null && remotes.isNotEmpty()) runCatching { session?.shell("rm -f ${remotes.joinToString(" ") { "'$it'" }}") }
            drop()
        }
    }

    private val DIRECTORIES = listOf("/data/local/tmp", "/sdcard/Download", "/sdcard")

    private fun remoteSize(s: AdbSession, remote: String): Long =
        s.shell("wc -c < '$remote' 2>/dev/null || stat -c %s '$remote' 2>/dev/null")
            .trim().lineSequence().lastOrNull()?.trim()?.toLongOrNull() ?: 0L

    /** Provskriver till varje möjlig mapp och returnerar den första där skrivning och storlekskontroll fungerar. */
    private fun probeDirectory(s: AdbSession, errors: MutableList<String>): String? {
        val block = ByteArray(1124) { it.toByte() }
        for (dir in DIRECTORIES) {
            val path = "$dir/.overforapk-probe"
            try {
                s.shell("rm -f '$path'")
                s.append(path, block, block.size)
                val size = remoteSize(s, path)
                s.shell("rm -f '$path'")
                if (size == 1124L) return dir
                errors += "$dir: fel filstorlek ($size i stället för 1124)"
            } catch (e: RemoteWriteException) {
                errors += "$dir: ${e.message}"
            }
        }
        return null
    }

    private fun diagnose(s: AdbSession, remote: String): String = runCatching {
        s.shell("ls -l '$remote' 2>&1; wc -c < '$remote' 2>&1; df /data/local/tmp 2>&1 | tail -n 2").trim().take(600)
    }.getOrDefault("(kunde inte läsa diagnos)")

    private fun remoteWriteMessage(e: RemoteWriteException): String {
        val text = e.message ?: ""
        return if (text.contains("No space", ignoreCase = true) || text.contains("ENOSPC"))
            "TV:n har för lite ledigt lagringsutrymme för att ta emot filen.\n\nTekniskt: $text"
        else "TV:n kunde inte spara filen.\n\nTekniskt: $text"
    }

    private fun sendFrom(s: AdbSession, file: File, remote: String, offset: Long, chunk: Int, onProgress: (Long) -> Unit) {
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(chunk)
            var pos = offset
            val length = file.length()
            while (pos < length) {
                val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), length - pos).toInt())
                if (n <= 0) break
                s.append(remote, buffer, n)
                pos += n
                onProgress(pos)
            }
        }
    }
}

/** Översätter pm:s felkoder till begripliga svenska förklaringar. */
object InstallErrors {
    private val code = Regex("INSTALL_[A-Z_]+")

    fun explain(raw: String, apkAbis: Set<String>, deviceAbis: List<String>): String {
        val text = raw.trim()
        val c = code.find(text)?.value
        val detail = text.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: "inget svar"
        if (c == "INSTALL_FAILED_NO_MATCHING_ABIS") {
            return AbiCheck.explainFailure("Installationen misslyckades: $text", apkAbis, deviceAbis)
        }
        val reason = when (c) {
            "INSTALL_FAILED_ALREADY_EXISTS" -> "Appen finns redan på TV:n och kunde inte ersättas."
            "INSTALL_FAILED_VERSION_DOWNGRADE" ->
                "TV:n har en nyare version av appen än filen du skickar. Avinstallera appen på TV:n först om du vill gå tillbaka (appens data raderas då)."
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE", "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE" ->
                "Appen på TV:n är signerad med en annan nyckel än filen (till exempel installerad från Play Butik). Android tillåter inte uppdatering över en annan signatur. Avinstallera appen på TV:n först, men då raderas dess data."
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> "TV:n har för lite ledigt lagringsutrymme. Ta bort något på TV:n och försök igen."
            "INSTALL_FAILED_OLDER_SDK" -> "Appen kräver en nyare Android-version än den TV:n har."
            "INSTALL_FAILED_NEWER_SDK" -> "Appen är byggd för en äldre Android-version och TV:n avvisar den."
            "INSTALL_FAILED_TEST_ONLY" -> "Filen är en test-APK som TV:n inte tillåter."
            "INSTALL_FAILED_USER_RESTRICTED" ->
                "TV:n tillåter inte installation via ADB. Titta efter en inställning som \"Installera via USB\" eller \"USB-installation\" under Utvecklaralternativ på TV:n."
            "INSTALL_FAILED_VERIFICATION_FAILURE", "INSTALL_FAILED_VERIFICATION_TIMEOUT" ->
                "TV:ns säkerhetskontroll (till exempel Play Protect) stoppade installationen."
            "INSTALL_FAILED_MISSING_SHARED_LIBRARY" -> "Appen kräver ett systembibliotek som TV:n saknar."
            "INSTALL_FAILED_DUPLICATE_PERMISSION", "INSTALL_FAILED_CONFLICTING_PROVIDER" ->
                "Appen krockar med en annan app på TV:n. Avinstallera den andra appen först."
            "INSTALL_FAILED_INVALID_APK", "INSTALL_PARSE_FAILED_NOT_APK", "INSTALL_PARSE_FAILED_MANIFEST_MALFORMED",
            "INSTALL_PARSE_FAILED_NO_CERTIFICATES", "INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION", "INSTALL_FAILED_INVALID_URI" ->
                "Filen är skadad, ofullständig eller osignerad. Hämta den på nytt och försök igen."
            null -> if (text.isBlank()) "TV:n gav inget svar på installationen." else "TV:n avvisade installationen."
            else -> "TV:n avvisade installationen."
        }
        return "$reason\n\nSvar från TV:n: $detail"
    }
}
