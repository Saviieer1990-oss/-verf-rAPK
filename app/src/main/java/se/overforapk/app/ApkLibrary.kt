package se.overforapk.app

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

data class ApkEntry(
    val id: String,
    val file: File,
    val appName: String,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long,
    val icon: Drawable?,
    val displayName: String,
    val isSplitArchive: Boolean
)

class ApkLibrary(private val context: Context) {
    private val folder = File(context.filesDir, "apk-library").apply { mkdirs() }
    private val extractedFolder = File(context.cacheDir, "apk-parts").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("apk-library", Context.MODE_PRIVATE)
    private val secrets by lazy { SecureSecretStore(context, "github-secrets") }

    fun import(uri: Uri, suggestedName: String?): ApkEntry {
        val name = suggestedName?.takeIf { it.isNotBlank() } ?: queryDisplayName(uri)
        return importStream(name) { context.contentResolver.openInputStream(uri) }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull()

    fun importStream(suggestedName: String?, open: () -> InputStream?): ApkEntry {
        val safeName = (suggestedName ?: "app-${System.currentTimeMillis()}.apk")
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        require(safeName.substringAfterLast('.', "").lowercase() in setOf("apk", "apks", "xapk")) {
            "Välj en APK-, APKS- eller XAPK-fil."
        }
        val destination = File(folder, "${System.currentTimeMillis()}-$safeName")
        try {
            open().use { input ->
                requireNotNull(input) { "Filen kunde inte öppnas." }.use { source ->
                    destination.outputStream().use { output -> source.copyTo(output) }
                }
            }
            require(destination.length() > 0L) { "Filen är tom." }
            val added = inspect(destination)
            require(added.packageName != null) { "Kunde inte läsa appinformationen. Kontrollera att arkivet innehåller en giltig APK." }
            if (added.packageName != null && !keepOlderVersions()) {
                all().filter {
                    it.file != destination && it.packageName == added.packageName
                }.forEach { delete(it) }
            }
            return added
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    fun all(): List<ApkEntry> = folder.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in setOf("apk", "apks", "xapk") }
        ?.mapNotNull { file -> runCatching { inspect(file) }.getOrNull() }
        ?.sortedByDescending { it.file.lastModified() }
        ?: emptyList()

    fun rename(entry: ApkEntry, name: String) {
        prefs.edit().putString(entry.file.name, name.trim()).apply()
    }

    fun delete(entry: ApkEntry) {
        entry.file.delete()
        prefs.edit().remove(entry.file.name).apply()
    }

    fun githubUser(): String = prefs.getString("gh_user", "") ?: ""

    @Synchronized
    fun githubToken(): String {
        val encrypted = secrets.get("token")
        if (encrypted.isNotEmpty()) return encrypted
        // Migrate tokens saved by earlier test builds out of plain-text preferences.
        val legacy = prefs.getString("gh_token", "").orEmpty()
        if (legacy.isNotEmpty()) {
            runCatching {
                secrets.put("token", legacy)
                prefs.edit().remove("gh_token").apply()
            }
        }
        return legacy
    }

    @Synchronized
    fun setGithub(user: String, token: String) {
        if (token.isNotBlank()) secrets.put("token", token.trim()) else secrets.put("token", "")
        prefs.edit().putString("gh_user", user.trim()).remove("gh_token").apply()
    }

    fun tvDevicesJson(): String = prefs.getString("tv_devices", "[]") ?: "[]"
    fun setTvDevicesJson(json: String) { prefs.edit().putString("tv_devices", json).apply() }

    fun lastTvKey(): String = prefs.getString("tv_last", "") ?: ""
    fun setLastTvKey(key: String) { prefs.edit().putString("tv_last", key).apply() }

    fun adbKeyFiles(): Pair<File, File> {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        return File(dir, "adbkey") to File(dir, "adbkey.pub")
    }

    fun keepOlderVersions(): Boolean = prefs.getBoolean("keep_older_versions", false)

    fun setKeepOlderVersions(keep: Boolean) {
        prefs.edit().putBoolean("keep_older_versions", keep).apply()
    }

    fun apkParts(entry: ApkEntry): List<File> {
        if (entry.file.extension.equals("apk", true)) return listOf(entry.file)
        val target = File(extractedFolder, entry.file.nameWithoutExtension + "-" +
            entry.file.length() + "-" + entry.file.lastModified())
        if (target.isDirectory) {
            val cached = target.listFiles()?.filter { it.extension.equals("apk", true) }?.sortedBy { it.name }
            if (!cached.isNullOrEmpty()) return cached
        }
        target.deleteRecursively()
        target.mkdirs()
        val extracted = mutableListOf<File>()
        var totalBytes = 0L
        ZipInputStream(entry.file.inputStream().buffered()).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: break
                if (!item.isDirectory && item.name.endsWith(".apk", true)) {
                    require(extracted.size < 40) { "Arkivet innehåller för många APK-delar." }
                    val output = File(target, item.name.substringAfterLast('/').ifBlank { "part-${extracted.size}.apk" })
                    var count = 0L
                    output.outputStream().buffered().use { stream ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            count += read
                            totalBytes += read
                            require(totalBytes <= 1024L * 1024L * 1024L) { "APK-arkivet är för stort." }
                            stream.write(buffer, 0, read)
                        }
                    }
                    if (count > 0) extracted.add(output) else output.delete()
                }
                zip.closeEntry()
            }
        }
        require(extracted.isNotEmpty()) { "Arkivet innehåller inga APK-filer." }
        return extracted.sortedBy { it.name }
    }

    private fun inspect(file: File): ApkEntry {
        val metadataApk = if (file.extension.equals("apk", true)) file else {
            runCatching {
                val parts = apkPartsForInspection(file)
                parts.firstOrNull { it.name.equals("base.apk", true) } ?: parts.firstOrNull()
            }.getOrNull()
        }
        val info = metadataApk?.let { parsePackageInfo(it) }
        val pm = context.packageManager
        val appInfo = info?.applicationInfo
        if (appInfo != null && metadataApk != null) {
            appInfo.sourceDir = metadataApk.absolutePath
            appInfo.publicSourceDir = metadataApk.absolutePath
        }
        val originalName = appInfo?.loadLabel(pm)?.toString() ?: file.nameWithoutExtension
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode ?: 0L else {
            @Suppress("DEPRECATION")
            (info?.versionCode?.toLong() ?: 0L)
        }
        val id = file.name
        return ApkEntry(
            id = id, file = file, appName = originalName, packageName = info?.packageName,
            versionName = info?.versionName, versionCode = versionCode,
            icon = appInfo?.loadIcon(pm), displayName = prefs.getString(id, originalName) ?: originalName,
            isSplitArchive = !file.extension.equals("apk", true)
        )
    }

    private fun apkPartsForInspection(file: File): List<File> {
        val target = File(extractedFolder, "inspect-" + file.nameWithoutExtension + "-" + file.length())
        if (target.isDirectory) {
            val cached = target.listFiles()?.filter { it.extension.equals("apk", true) }
            if (!cached.isNullOrEmpty()) return cached
        }
        target.deleteRecursively()
        target.mkdirs()
        val result = mutableListOf<File>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: break
                if (!item.isDirectory && item.name.endsWith(".apk", true)) {
                    val out = File(target, item.name.substringAfterLast('/'))
                    out.outputStream().buffered().use { zip.copyTo(it) }
                    if (out.length() > 0) result.add(out) else out.delete()
                    zip.closeEntry()
                    if (result.size >= 40) break
                    continue
                }
                zip.closeEntry()
            }
        }
        return result
    }

    private fun parsePackageInfo(file: File) =
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(
                    (PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA).toLong()
                )
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath, PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
            )
        }
}
