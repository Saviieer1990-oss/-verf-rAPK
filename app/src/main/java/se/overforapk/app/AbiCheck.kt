package se.overforapk.app

import java.io.File
import java.util.Collections
import java.util.zip.ZipFile

/**
 * Jämför vilka processorarkitekturer (ABI) en APK har inbyggd kod för med vad TV:n stöder.
 * Fångar INSTALL_FAILED_NO_MATCHING_ABIS innan filen skickas, och förklarar felet om det ändå uppstår.
 */
object AbiCheck {
    /** Shell-kommando som skriver ut TV:ns ABI:er (en egenskap per rad). abilist är det pm faktiskt använder. */
    const val QUERY_COMMAND =
        "getprop ro.product.cpu.abilist; getprop ro.product.cpu.abi; getprop ro.product.cpu.abi2"

    private val libEntry = Regex("""lib/([^/]+)/[^/]+\.so""")

    private val knownAbis = setOf(
        "arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "mips64", "mips", "riscv64"
    )

    /**
     * ABI:er som APK-delarna har .so-filer för. Tom mängd betyder ingen inbyggd kod,
     * alltså att appen fungerar på alla arkitekturer.
     */
    fun apkAbis(parts: List<File>): Set<String> {
        val result = linkedSetOf<String>()
        for (part in parts) {
            runCatching {
                ZipFile(part).use { zip ->
                    for (item in Collections.list(zip.entries())) {
                        if (item.isDirectory) continue
                        val abi = libEntry.matchEntire(item.name)?.groupValues?.get(1)
                        if (abi != null) result.add(abi)
                    }
                }
            }
        }
        return result
    }

    /** Läser ABI-listan ur utdata från [QUERY_COMMAND]. Okända ord (till exempel felmeddelanden) ignoreras. */
    fun parseDeviceAbis(output: String): List<String> =
        output.split(',', '\n', '\r', ' ', '\t')
            .map { it.trim() }
            .filter { it in knownAbis }
            .distinct()

    /** Ger ett felmeddelande om APK:n bevisligen inte passar TV:n, annars null (även när något är okänt). */
    fun mismatch(apkAbis: Set<String>, deviceAbis: List<String>): String? {
        if (apkAbis.isEmpty() || deviceAbis.isEmpty()) return null
        if (apkAbis.any { it in deviceAbis }) return null
        return "Appen passar inte TV:ns processor. TV:n stöder ${deviceAbis.joinToString(", ")}, " +
            "men APK-filen har bara kod för ${apkAbis.joinToString(", ")}. " +
            "Hämta en version av appen för ${deviceAbis.first()} (eller en universal APK) och försök igen. " +
            "Inget skickades till TV:n."
    }

    /** Lägger till en förklaring om TV:n själv avvisade installationen på grund av arkitekturen. */
    fun explainFailure(text: String, apkAbis: Set<String>, deviceAbis: List<String>): String {
        if (!text.contains("NO_MATCHING_ABIS")) return text
        val tv = if (deviceAbis.isEmpty()) "okänt" else deviceAbis.joinToString(", ")
        val apk = if (apkAbis.isEmpty()) "okänt" else apkAbis.joinToString(", ")
        return "$text\n\nAPK-filen passar inte TV:ns processor. TV:n stöder: $tv. " +
            "APK-filen har kod för: $apk. Hämta en version för TV:ns arkitektur (eller en universal APK)."
    }
}
