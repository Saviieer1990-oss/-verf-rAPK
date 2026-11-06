package se.overforapk.app

import java.io.File

/**
 * Installation där TV:n hämtar filen från mobilen (HTTP) i stället för att mobilen skickar data över ADB.
 * Över ADB går bara korta kommandon utan indata: starta nedladdning, fråga om den är klar, pm install.
 * Nedladdningen körs i bakgrunden på TV:n (nohup), så en tillfälligt bruten ADB-anslutning stoppar den inte.
 */
object PullInstaller {
    private val DIRECTORIES = listOf("/data/local/tmp", "/sdcard/Download")
    private const val NO_CONNECTION_MS = 30_000L
    private const val STALL_MS = 60_000L
    private const val MAX_POLL_FAILURES = 20
    private const val OK_NAME = ".overforapk-pull.ok"
    private const val FAIL_NAME = ".overforapk-pull.fail"

    /**
     * Returnerar slutresultatet, eller null om metoden inte går att använda på den här TV:n
     * (orsaken skickas då till [onFallback] och den gamla metoden provas).
     */
    fun run(
        open: () -> AdbSession,
        parts: List<File>,
        tvHost: String,
        apkAbis: Set<String>,
        appName: String,
        deviceName: String,
        onStatus: (String) -> Unit,
        cancelled: () -> Boolean,
        onFallback: (String) -> Unit
    ): Outcome? {
        val ip = PullServer.localAddressFor(tvHost)
        if (ip == null) {
            onFallback("kunde inte avgöra mobilens adress på nätverket")
            return null
        }

        var session: AdbSession? = null
        var remotes: List<String> = emptyList()
        var markers: List<String> = emptyList()
        fun current(): AdbSession = session ?: open().also { session = it }
        fun drop() { runCatching { session?.close() }; session = null }

        try {
            PullServer(parts, tvHost).use { server ->
                onStatus("Ansluter till $deviceName… Godkänn felsökningen på TV:n om en fråga visas (bocka i Tillåt alltid).")
                val first = current()

                var deviceAbis: List<String> = emptyList()
                if (apkAbis.isNotEmpty()) {
                    deviceAbis = AbiCheck.parseDeviceAbis(runCatching { first.shell(AbiCheck.QUERY_COMMAND) }.getOrDefault(""))
                    AbiCheck.mismatch(apkAbis, deviceAbis)?.let { return Outcome(false, it) }
                }

                val dir = DIRECTORIES.firstOrNull { d ->
                    runCatching { first.shell("touch '$d/.overforapk-w' && rm -f '$d/.overforapk-w' && echo ok") }
                        .getOrDefault("").contains("ok")
                }
                if (dir == null) {
                    onFallback("TV:n tillåter inte att filer sparas")
                    return null
                }

                val ok = "$dir/$OK_NAME"
                val fail = "$dir/$FAIL_NAME"
                remotes = parts.indices.map { "$dir/overforapk-pull-$it.apk" }
                markers = listOf(ok, fail)
                val quotedAll = (remotes + markers).joinToString(" ") { "'$it'" }

                val steps = parts.indices.joinToString(" && ") { i ->
                    val r = remotes[i]
                    val u = server.url(ip, i)
                    "{ curl -fsS -o \"$r\" \"$u\" || wget -q -O \"$r\" \"$u\" || toybox wget -q -O \"$r\" \"$u\"; } " +
                        "&& [ \"\$(wc -c < \"$r\")\" = ${parts[i].length()} ]"
                }
                val inner = "$steps && touch \"$ok\" || touch \"$fail\""
                onStatus("TV:n hämtar filen från mobilen…")
                first.shell("rm -f $quotedAll; nohup sh -c '$inner' >/dev/null 2>&1 </dev/null & echo started")

                val startedAt = System.currentTimeMillis()
                var lastPoll = 0L
                var lastPercent = -1
                var failures = 0
                while (true) {
                    if (cancelled()) return Outcome(false, InstallEngine.CANCELLED_TEXT)
                    val now = System.currentTimeMillis()

                    val percent = if (server.total == 0L) 100 else (server.delivered * 100 / server.total).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onStatus("TV:n hämtar från mobilen… $percent % (${formatBytes(server.delivered)} av ${formatBytes(server.total)})")
                    }

                    if (now - lastPoll >= 2_000) {
                        lastPoll = now
                        try {
                            val answer = current().shell("ls '$ok' '$fail' 2>/dev/null")
                            failures = 0
                            if (answer.contains(OK_NAME)) break
                            if (answer.contains(FAIL_NAME)) {
                                onFallback(
                                    if (server.delivered == 0L) "TV:n kunde inte hämta filen (curl/wget saknas eller TV:n når inte mobilen)"
                                    else "nedladdningen på TV:n avbröts"
                                )
                                return null
                            }
                        } catch (e: NeedsPairingException) {
                            throw e
                        } catch (e: AuthDeniedException) {
                            throw e
                        } catch (e: Exception) {
                            // Nedladdningen pågår på TV:n oavsett. Återanslut och fråga igen.
                            if (cancelled()) return Outcome(false, InstallEngine.CANCELLED_TEXT)
                            drop()
                            if (++failures >= MAX_POLL_FAILURES) {
                                onFallback("tappade anslutningen till TV:n: ${e.message ?: e.javaClass.simpleName}")
                                return null
                            }
                            onStatus("Anslutningen till TV:n bröts. Återansluter… (nedladdningen fortsätter på TV:n)")
                            Thread.sleep(1_500)
                        }
                    }

                    if (server.connections == 0 && now - startedAt > NO_CONNECTION_MS) {
                        onFallback("TV:n nådde inte mobilen (kontrollera att routern inte blockerar enheter från varandra) eller saknar curl/wget")
                        return null
                    }
                    if (server.connections > 0 && server.active == 0 && now - server.lastActivity > STALL_MS) {
                        onFallback("nedladdningen stannade")
                        return null
                    }
                    Thread.sleep(500)
                }

                onStatus("Filen är framme. Installerar från TV:ns lagring…")
                val list = remotes.joinToString(" ") { "'$it'" }
                val command = if (parts.size == 1) "pm install ${InstallEngine.OPTIONS} ${list}"
                else "pm install-multiple ${InstallEngine.OPTIONS} $list"
                var response: String? = null
                var lastError = ""
                for (attempt in 1..3) {
                    if (cancelled()) return Outcome(false, InstallEngine.CANCELLED_TEXT)
                    try {
                        val s = current()
                        s.shell("chmod 644 $list")
                        response = s.shell(command)
                        break
                    } catch (e: NeedsPairingException) {
                        throw e
                    } catch (e: AuthDeniedException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e.message ?: e.javaClass.simpleName
                        drop()
                        Thread.sleep(1_500)
                    }
                }
                if (response == null) {
                    return Outcome(false, "Filen kom fram men TV:n svarade inte på installationen.\n\nTekniskt: $lastError")
                }
                return if (InstallEngine.isSuccess(response)) InstallEngine.success(current(), appName, deviceName)
                else Outcome(false, InstallErrors.explain(response, apkAbis, deviceAbis))
            }
        } finally {
            if (remotes.isNotEmpty() && !cancelled()) {
                val all = (remotes + markers).joinToString(" ") { "'$it'" }
                runCatching { (session ?: open()).also { session = it }.shell("rm -f $all") }
            }
            drop()
        }
    }
}
