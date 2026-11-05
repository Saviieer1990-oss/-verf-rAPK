package se.overforapk.app

import java.io.File
import java.io.IOException

/** En uppkopplad ADB-session mot en TV. Implementeras av både dadb (port 5555) och libadb (Trådlös felsökning). */
interface AdbSession : AutoCloseable {
    /** Kör ett kommando på TV:n och returnerar utdata. */
    fun shell(command: String): String

    /** Skickar [file] till [remotePath] på TV:n. Kastar vid fel. */
    fun push(file: File, remotePath: String)

    /** Direkt installation via en ström (cmd package install -S). Returnerar TV:ns svar som text. */
    fun streamInstall(parts: List<File>): String
}

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

    private fun pause(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    fun install(
        open: () -> AdbSession,
        parts: List<File>,
        appName: String,
        deviceName: String,
        onStatus: (String) -> Unit = {}
    ): Outcome {
        val apkAbis = AbiCheck.apkAbis(parts)
        var deviceAbis: List<String> = emptyList()
        var lastError = ""

        // Steg 1: direkt ström. Bryts anslutningen försöker vi en gång till med ny anslutning.
        for (attempt in 1..2) {
            try {
                open().use { s ->
                    if (attempt == 1 && apkAbis.isNotEmpty()) {
                        deviceAbis = AbiCheck.parseDeviceAbis(runCatching { s.shell(AbiCheck.QUERY_COMMAND) }.getOrDefault(""))
                    }
                    AbiCheck.mismatch(apkAbis, deviceAbis)?.let { return Outcome(false, it) }
                    onStatus(
                        if (attempt == 1) "Installerar $appName på $deviceName…"
                        else "Anslutningen bröts. Återansluter och försöker igen…"
                    )
                    val response = s.streamInstall(parts)
                    if (isSuccess(response)) return success(s, appName, deviceName)
                    // TV:n har läst filen och sagt nej: försök inte igen, förklara varför.
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
            if (attempt == 1) pause(1_200)
        }

        // Steg 2: reservmetod via filen på TV:ns lagring.
        onStatus("Direkt installation fungerade inte. Skickar filen till TV:n och installerar därifrån…")
        for (attempt in 1..2) {
            try {
                open().use { s ->
                    val tag = System.currentTimeMillis()
                    val remotes = parts.indices.map { "/data/local/tmp/overforapk-$tag-$it.apk" }
                    try {
                        parts.forEachIndexed { i, file ->
                            onStatus(
                                if (parts.size == 1) "Skickar filen till TV:n…"
                                else "Skickar del ${i + 1} av ${parts.size} till TV:n…"
                            )
                            s.push(file, remotes[i])
                            val size = s.shell("chmod 644 ${remotes[i]}; wc -c < ${remotes[i]}")
                                .trim().lineSequence().lastOrNull()?.trim()?.toLongOrNull()
                            if (size != file.length()) {
                                throw IOException("Filen kom inte fram helt till TV:n (${size ?: 0} av ${file.length()} byte).")
                            }
                        }
                        onStatus("Installerar från TV:ns lagring…")
                        val command = if (parts.size == 1) "pm install $OPTIONS ${remotes[0]}"
                        else "pm install-multiple $OPTIONS ${remotes.joinToString(" ")}"
                        val response = s.shell(command)
                        return if (isSuccess(response)) success(s, appName, deviceName)
                        else Outcome(false, InstallErrors.explain(response, apkAbis, deviceAbis))
                    } finally {
                        runCatching { s.shell("rm -f ${remotes.joinToString(" ")}") }
                    }
                }
            } catch (e: NeedsPairingException) {
                throw e
            } catch (e: AuthDeniedException) {
                return Outcome(false, e.message ?: TvText.DENIED)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
            if (attempt == 1) {
                onStatus("Anslutningen bröts. Återansluter och skickar filen igen…")
                pause(1_500)
            }
        }

        return Outcome(
            false,
            "Överföringen till TV:n avbröts flera gånger, även med reservmetoden. " +
                "Kontrollera Wi-Fi-signalen (TV:n på kabel är stabilast), starta om TV:n eller slå av och på " +
                "felsökningen och försök igen.\n\nTekniskt: $lastError"
        )
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
