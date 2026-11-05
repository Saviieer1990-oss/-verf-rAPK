package se.overforapk.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NO_TV_ICON_HINT =
    "\n\nAppen saknar TV-ikon och syns därför inte på TV:ns startsida. " +
        "Starta den med knappen Öppna nedan, eller under Inställningar → Appar → Visa alla appar på TV:n."

@Composable
private fun StepHeader(number: Int, title: String) {
    Row(Modifier.padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Accent, shape = RoundedCornerShape(50), modifier = Modifier.size(26.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$number", color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChoiceCard(selected: Boolean, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF1B3550) else Panel),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                null, tint = if (selected) Accent else Muted
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), content = content)
            trailing?.invoke()
        }
    }
}

/** Guide: välj app, välj TV, installera. */
@Composable
internal fun ColumnScope.SendPage(
    library: ApkLibrary,
    entries: List<ApkEntry>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onPickFile: () -> Unit,
    onHelp: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = remember { TvInstaller(library, context.applicationContext) }
    var devices by remember { mutableStateOf(TvDevices.parse(library.tvDevicesJson())) }
    var selectedKey by remember { mutableStateOf(library.lastTvKey()) }
    var found by remember { mutableStateOf<List<FoundDevice>>(emptyList()) }
    var scanProgress by remember { mutableStateOf<Int?>(null) }
    var scanned by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var tvVersion by remember { mutableStateOf<Long?>(null) }
    var checkingTvVersion by remember { mutableStateOf(false) }
    var tvCheckFailed by remember { mutableStateOf(false) }
    var hasTvIcon by remember { mutableStateOf<Boolean?>(null) }
    var pairingFor by remember { mutableStateOf<TvDevice?>(null) }
    var manual by remember { mutableStateOf(false) }
    var manualName by remember { mutableStateOf("") }
    var manualAddress by remember { mutableStateOf("") }

    val entry = entries.firstOrNull { it.id == selectedId }
    val device = devices.firstOrNull { it.key == selectedKey }
    val fresh = found.filter { f -> devices.none { it.host == f.host && it.wireless == f.wireless } }

    LaunchedEffect(device?.key, entry?.packageName) {
        tvVersion = null
        tvCheckFailed = false
        val currentDevice = device
        val packageName = entry?.packageName
        if (currentDevice != null && packageName != null) {
            checkingTvVersion = true
            val checked = withContext(Dispatchers.IO) {
                runCatching { installer.installedVersion(currentDevice, packageName) }
            }
            tvVersion = checked.getOrNull()
            tvCheckFailed = checked.isFailure || checked.getOrNull() == null
            checkingTvVersion = false
        } else {
            checkingTvVersion = false
        }
    }

    // Skärmen får inte släckas under överföringen, då kan Android stänga av Wi-Fi-anslutningen
    val view = LocalView.current
    DisposableEffect(busy != null) {
        view.keepScreenOn = busy != null
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = busy != null) { onMessage("Vänta tills det är klart. Avbryts det nu kan installationen skadas.") }

    LaunchedEffect(entry?.id) {
        hasTvIcon = null
        val e = entry
        if (e != null) {
            hasTvIcon = withContext(Dispatchers.IO) {
                runCatching { TvCheck.hasLeanbackLauncher(library.apkParts(e)) }.getOrNull()
            }
        }
    }

    fun saveDevices(list: List<TvDevice>) { devices = list; library.setTvDevicesJson(TvDevices.toJson(list)) }
    fun choose(d: TvDevice) { selectedKey = d.key; library.setLastTvKey(d.key); outcome = null }

    fun runScan() {
        if (scanProgress != null) return
        scope.launch {
            scanProgress = 0; outcome = null
            val result = TvFinder.scan(context) { scanProgress = it }
            scanProgress = null; scanned = true
            if (result == null) onMessage("Hittade inget Wi-Fi. Anslut mobilen till samma nätverk som TV:n.")
            else found = result
        }
    }

    fun install() {
        val d = device ?: return
        val e = entry ?: return
        scope.launch {
            outcome = null
            busy = "Installerar ${e.displayName} på ${d.name}… Titta på TV:n om en fråga visas."
            val r = runCatching { withContext(Dispatchers.IO) { installer.install(d, e) { status -> busy = status } } }
            busy = null
            val ex = r.exceptionOrNull()
            when {
                ex is NeedsPairingException -> pairingFor = d
                ex != null -> outcome = Outcome(false, ex.message ?: "Installationen misslyckades.")
                else -> {
                    val o = r.getOrThrow()
                    outcome = if (o.ok && hasTvIcon == false) o.copy(text = o.text + NO_TV_ICON_HINT) else o
                    if (o.ok) tvVersion = e.versionCode
                    onMessage(o.text)
                    val model = o.model
                    if (o.ok && !model.isNullOrBlank() && d.name.startsWith("TV ")) {
                        saveDevices(devices.map { if (it.key == d.key) it.copy(name = model) else it })
                    }
                }
            }
        }
    }

    fun launchOnTv() {
        val d = device ?: return
        val e = entry ?: return
        val pkg = e.packageName ?: return
        scope.launch {
            outcome = null
            busy = "Startar ${e.displayName} på ${d.name}…"
            val r = runCatching { withContext(Dispatchers.IO) { installer.launch(d, pkg, e.displayName) } }
            busy = null
            val ex = r.exceptionOrNull()
            when {
                ex is NeedsPairingException -> pairingFor = d
                ex != null -> outcome = Outcome(false, ex.message ?: "Kunde inte starta appen.")
                else -> outcome = r.getOrThrow()
            }
        }
    }

    LaunchedEffect(Unit) { if (devices.isEmpty()) runScan() }

    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Skicka app till TV", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold) }

        // Steg 1
        item { StepHeader(1, "Välj app") }
        if (entries.isEmpty()) {
            item { EmptyState("Ingen app vald än", "Lägg till APK-filen du vill flytta till TV:n.") }
        }
        items(entries, key = { "app-${it.id}" }) { e ->
            ChoiceCard(selected = e.id == selectedId, onClick = { onSelect(e.id); outcome = null }) {
                Text(e.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("${e.versionName ?: "Okänd version"}  •  ${formatBytes(e.file.length())}", color = Muted, fontSize = 11.sp)
            }
        }
        item {
            OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Text("  Lägg till APK-fil från mobilen")
            }
        }

        if (entry != null && hasTvIcon == false) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2F12)), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFFFC857))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Appen saknar TV-ikon", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                "Den går att installera men syns inte på TV:ns startsida, och kan vara svår att styra med fjärrkontrollen. " +
                                    "Efter installationen startar du den med Öppna-knappen eller under Inställningar → Appar → Visa alla appar.",
                                color = Muted, fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Steg 2
        if (entry != null && device != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(13.dp)) {
                        Text("Appstatus på ${device.name}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        val status = when {
                            checkingTvVersion -> "Kontrollerar installerad version…"
                            tvCheckFailed -> "Kunde inte läsa status. Kontrollera anslutningen och att du valt Tillåt (inte Ej tillåt) på TV:n."
                            tvVersion == null -> "Status ej kontrollerad"
                            tvVersion == 0L -> "Inte installerad på TV:n"
                            tvVersion!! < entry.versionCode -> "Installerad på TV:n, men filen i mobilen är nyare"
                            tvVersion!! > entry.versionCode -> "Installerad på TV:n (nyare version än filen i mobilen)"
                            else -> "Installerad på TV:n (samma version)"
                        }
                        Text(status, color = if (tvVersion != null && tvVersion!! > 0L && tvVersion!! < entry.versionCode) Accent else Muted, fontSize = 12.sp)
                    }
                }
            }
        }

        item { StepHeader(2, "Välj TV") }
        items(devices, key = { "tv-${it.key}" }) { d ->
            ChoiceCard(
                selected = d.key == selectedKey,
                onClick = { choose(d) },
                trailing = {
                    IconButton(onClick = {
                        saveDevices(devices.filterNot { it.key == d.key })
                        if (selectedKey == d.key) selectedKey = ""
                    }) { Icon(Icons.Default.Delete, "Ta bort", tint = Muted) }
                }
            ) {
                Text(d.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    d.host + if (d.wireless) "  •  Trådlös felsökning" else "  •  ADB port ${d.port}",
                    color = Muted, fontSize = 11.sp
                )
            }
        }
        scanProgress?.let { p ->
            item {
                Text("Söker efter TV:ar på Wi-Fi… $p %", color = Accent, fontSize = 12.sp)
                LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        items(fresh, key = { "found-${it.host}-${it.wireless}" }) { f ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hittad enhet: ${f.host}", color = Color.White, fontSize = 14.sp)
                        Text(if (f.wireless) "Trådlös felsökning" else "ADB port 5555", color = Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        val d = TvDevice("TV ${f.host}", f.host, f.port, f.wireless)
                        saveDevices(devices.filterNot { it.host == f.host } + d)
                        choose(d)
                    }) { Text("Använd") }
                }
            }
        }
        if (scanned && scanProgress == null && devices.isEmpty() && fresh.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Hittade ingen TV", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            "Kontrollera att TV:n är på, att ni är på samma Wi-Fi och att felsökning är påslagen på TV:n.",
                            color = Muted, fontSize = 12.sp
                        )
                        TextButton(onClick = onHelp) { Text("Visa guide för TV:n") }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = scanProgress == null && busy == null, onClick = { runScan() }) {
                    Icon(Icons.Default.Search, null); Text("  Sök igen")
                }
                OutlinedButton(enabled = busy == null, onClick = { manual = true }) {
                    Icon(Icons.Default.Add, null); Text("  Ange IP")
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }

    // Steg 3: fast längst ner så att knappen och resultatet alltid syns
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        outcome?.let { o ->
            Card(
                colors = CardDefaults.cardColors(containerColor = if (o.ok) Color(0xFF123A2A) else Color(0xFF3A1A1A)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(if (o.ok) "Klart!" else "Det gick inte", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(o.text, color = Color.White, fontSize = 13.sp)
                    if (!o.ok) TextButton(onClick = onHelp) { Text("Visa guide för TV:n") }
                }
            }
        }
        busy?.let { b ->
            Text(b, color = Accent, fontSize = 12.sp)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (entry != null && entry.packageName != null && device != null) {
            val missingOnTv = tvVersion == 0L && outcome?.ok != true
            OutlinedButton(
                onClick = { launchOnTv() },
                enabled = busy == null && !checkingTvVersion && !missingOnTv,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null); Text("  Öppna ${entry.displayName} på TV:n")
            }
            if (missingOnTv) {
                Text("Öppna går att använda när appen finns på TV:n. Installera den först.", color = Muted, fontSize = 11.sp)
            }
        }
        Button(
            onClick = { install() },
            enabled = entry != null && device != null && busy == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        ) {
            Text(
                if (entry != null && device != null) "Installera ${entry.displayName} på ${device.name}"
                else "Välj app och TV först"
            )
        }
    }

    if (manual) {
        AlertDialog(
            onDismissRequest = { manual = false },
            title = { Text("Ange TV:ns IP-adress") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("IP-adressen visas på TV:n under Inställningar > Nätverk, eller under Trådlös felsökning.", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(manualAddress, { manualAddress = it }, singleLine = true, label = { Text("IP, t.ex. 192.168.1.20") })
                    OutlinedTextField(manualName, { manualName = it }, singleLine = true, label = { Text("Namn (valfritt)") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val addr = TvDevices.parseAddress(manualAddress)
                    if (addr == null) {
                        onMessage("Ogiltig adress. Skriv t.ex. 192.168.1.20.")
                    } else {
                        manual = false
                        val host = addr.first
                        scope.launch {
                            busy = "Kontrollerar $host…"
                            val legacy = withContext(Dispatchers.IO) { TvFinder.portOpen(host, addr.second) }
                            busy = null
                            val d = TvDevice(manualName.trim().ifBlank { "TV $host" }, host, if (legacy) addr.second else 0, wireless = !legacy)
                            saveDevices(devices.filterNot { it.host == host } + d)
                            choose(d)
                            manualAddress = ""; manualName = ""
                            if (!legacy) onMessage("Trådlös felsökning antas. Du får parkoppla första gången du installerar.")
                        }
                    }
                }) { Text("Spara") }
            },
            dismissButton = { TextButton(onClick = { manual = false }) { Text("Avbryt") } }
        )
    }

    pairingFor?.let { d ->
        PairDialog(
            device = d,
            installer = installer,
            onPaired = { pairingFor = null; install() },
            onCancel = { pairingFor = null }
        )
    }
}

/** Ber om parkopplingskoden. Appen hittar TV:ns parkopplingsport själv. */
@Composable
private fun PairDialog(device: TvDevice, installer: TvInstaller, onPaired: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var service by remember { mutableStateOf<AdbService?>(null) }
    var code by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(device.host) {
        val watcher = NsdWatcher(context, SERVICE_TLS_PAIRING) { list -> service = list.firstOrNull { it.host == device.host } }
        watcher.start()
        onDispose { watcher.stop() }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onCancel() },
        title = { Text("Parkoppla ${device.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TV:n behöver parkopplas med mobilen en enda gång:", fontSize = 13.sp)
                Text(
                    "1. På TV:n: Inställningar > System > Utvecklaralternativ > Trådlös felsökning.\n" +
                        "2. Välj \"Para enhet med parkopplingskod\".\n" +
                        "3. Skriv koden som visas på TV:n här.",
                    fontSize = 13.sp
                )
                Text(
                    if (service != null) "TV:n hittad. Skriv koden." else "Väntar på att parkopplingsfönstret öppnas på TV:n…",
                    color = if (service != null) Accent else Muted, fontSize = 12.sp
                )
                OutlinedTextField(
                    code, { code = it.filter(Char::isDigit).take(6) }, singleLine = true,
                    label = { Text("Kod, 6 siffror") }
                )
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = service != null && code.length == 6 && !working, onClick = {
                val s = service ?: return@TextButton
                working = true; error = null
                scope.launch {
                    val err = withContext(Dispatchers.IO) { installer.pair(s.host, s.port, code) }
                    working = false
                    if (err == null) onPaired() else error = err
                }
            }) { Text("Para och installera") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onCancel) { Text("Avbryt") } }
    )
}
