package se.overforapk.app

import java.io.File
import java.util.zip.ZipFile

/** Kontroller som rör hur en app visas och startas på en Android TV. */
object TvCheck {
    private const val LEANBACK = "android.intent.category.LEANBACK_LAUNCHER"
    private val leanbackUtf16 = LEANBACK.toByteArray(Charsets.UTF_16LE)
    private val leanbackUtf8 = LEANBACK.toByteArray(Charsets.UTF_8)

    /**
     * Har appen en aktivitet med TV-startikon (LEANBACK_LAUNCHER)? Utan den syns appen inte på TV:ns startsida.
     * Returnerar true/false, eller null om manifestet inte gick att läsa.
     * Manifestet är binärt XML där textvärden ligger i en strängtabell, så det räcker att leta efter strängen.
     */
    fun hasLeanbackLauncher(parts: List<File>): Boolean? {
        var readAny = false
        for (part in parts) {
            val manifest = runCatching {
                ZipFile(part).use { zip ->
                    zip.getEntry("AndroidManifest.xml")?.let { entry ->
                        zip.getInputStream(entry).use { it.readBytes() }
                    }
                }
            }.getOrNull() ?: continue
            readAny = true
            if (contains(manifest, leanbackUtf16) || contains(manifest, leanbackUtf8)) return true
        }
        return if (readAny) false else null
    }

    private fun contains(data: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || data.size < needle.size) return false
        val last = data.size - needle.size
        var i = 0
        while (i <= last) {
            if (data[i] == needle[0]) {
                var j = 1
                while (j < needle.size && data[i + j] == needle[j]) j++
                if (j == needle.size) return true
            }
            i++
        }
        return false
    }
}

/** Startar en installerad app på TV:n via ADB. */
object TvLaunch {
    /** Först vanlig startikon, sedan TV-startikon för appar som bara har den. */
    private fun commands(packageName: String) = listOf(
        "monkey -p $packageName -c android.intent.category.LAUNCHER 1",
        "monkey -p $packageName -c android.intent.category.LEANBACK_LAUNCHER 1"
    )

    private fun started(output: String) =
        output.contains("Events injected: 1") && !output.contains("No activities found")

    /** [run] kör ett shell-kommando på TV:n och returnerar utdata. */
    fun start(packageName: String, run: (String) -> String): Boolean =
        commands(packageName).any { cmd -> runCatching { started(run(cmd)) }.getOrDefault(false) }

    fun failedText(): String =
        "Kunde inte starta appen automatiskt. Öppna den på TV:n under Inställningar → Appar → Visa alla appar."
}

/** Texter när TV:n nekar anslutningen (användaren trycker "Ej tillåt" eller inte svarar). */
object TvText {
    const val DENIED =
        "TV:n tillät inte anslutningen. Titta på TV:ns skärm och välj Tillåt (bocka i Tillåt alltid) när frågan visas, och försök igen. " +
            "Har du tryckt Ej tillåt förut: öppna Inställningar > System > Utvecklaralternativ på TV:n, " +
            "välj Återkalla behörigheter för USB-felsökning och försök igen."

    const val DENIED_WIRELESS =
        "TV:n tillät inte anslutningen. Välj Tillåt när frågan visas på TV:n och försök igen. " +
            "Har du tryckt Ej tillåt förut: ta bort mobilen under Trådlös felsökning > Parkopplade enheter på TV:n, " +
            "parkoppla på nytt och försök igen."
}
