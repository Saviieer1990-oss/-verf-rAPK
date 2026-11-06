package se.overforapk.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class GhRepo(
    val name: String,
    val fullName: String,
    val description: String?,
    val isPrivate: Boolean
)

data class GhAsset(val id: Long, val name: String, val size: Long, val apiUrl: String)

data class GhRelease(
    val tag: String,
    val title: String,
    val prerelease: Boolean,
    val published: String,
    val assets: List<GhAsset>
)

/** Minimal GitHub REST-klient utan externa beroenden. Token är valfri (krävs för privata repos). */
class GithubClient(token: String?) {
    private val token = token?.trim().orEmpty()

    private fun connect(url: String, accept: String, withAuth: Boolean = true): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "OverforAPK")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (withAuth && token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun errorFor(code: Int, remaining: String?): String = when {
        code == 401 -> "GitHub avvisade token. Kontrollera att den är giltig."
        code == 403 && remaining == "0" -> "GitHubs gräns för anrop är nådd. Lägg till en token eller vänta en stund."
        code == 403 -> "Åtkomst nekad av GitHub (403)."
        code == 404 -> "Hittades inte. Kontrollera användarnamnet (privata repos kräver token)."
        else -> "GitHub svarade med fel $code."
    }

    private fun getJson(url: String): String {
        val c = connect(url, "application/vnd.github+json")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException(errorFor(code, c.getHeaderField("X-RateLimit-Remaining")))
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun repos(user: String): List<GhRepo> {
        val url = if (token.isNotEmpty())
            "https://api.github.com/user/repos?per_page=100&sort=updated&affiliation=owner"
        else {
            require(Regex("[A-Za-z0-9-]{1,39}").matches(user)) { "Ogiltigt GitHub-användarnamn." }
            "https://api.github.com/users/$user/repos?per_page=100&sort=updated"
        }
        val arr = JSONArray(getJson(url))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            GhRepo(
                name = it.getString("name"),
                fullName = it.getString("full_name"),
                description = it.optString("description").takeIf { d -> d.isNotBlank() && d != "null" },
                isPrivate = it.optBoolean("private")
            )
        }
    }

    fun releases(fullName: String): List<GhRelease> {
        require(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+").matches(fullName)) { "Ogiltigt repo-namn." }
        val arr = JSONArray(getJson("https://api.github.com/repos/$fullName/releases?per_page=10"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("draft") }
            .map { r ->
                GhRelease(
                    tag = r.optString("tag_name"),
                    title = r.optString("name").ifBlank { r.optString("tag_name") },
                    prerelease = r.optBoolean("prerelease"),
                    published = r.optString("published_at").take(10),
                    assets = parseAssets(r.optJSONArray("assets"))
                )
            }
            .filter { it.assets.isNotEmpty() }
    }

    private fun parseAssets(arr: JSONArray?): List<GhAsset> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { a ->
                val n = a.getString("name").lowercase()
                n.endsWith(".apk") || n.endsWith(".apks") || n.endsWith(".xapk")
            }
            .map { GhAsset(it.getLong("id"), it.getString("name"), it.getLong("size"), it.getString("url")) }
    }

    /**
     * Öppnar en release-fil. Asset-URL:en svarar med en omdirigering till en signerad adress.
     * Omdirigeringen följs manuellt utan Authorization-header, annars avvisas den av lagringstjänsten.
     */
    fun openAsset(asset: GhAsset): InputStream {
        var c = connect(asset.apiUrl, "application/octet-stream")
        var hops = 0
        while (c.responseCode in 300..399) {
            val next = c.getHeaderField("Location") ?: throw IOException("Omdirigering utan adress.")
            c.disconnect()
            if (++hops > 5) throw IOException("För många omdirigeringar.")
            require(next.startsWith("https://")) { "Oväntad nedladdningsadress." }
            c = connect(next, "application/octet-stream", withAuth = false)
        }
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw IOException(errorFor(code, null))
        }
        return c.inputStream
    }
}
