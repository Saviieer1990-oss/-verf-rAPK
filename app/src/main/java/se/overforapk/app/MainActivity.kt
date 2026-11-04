package se.overforapk.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

internal val Navy = Color(0xFF0A111B)
internal val Panel = Color(0xFF142233)
internal val Accent = Color(0xFF55C4D7)
internal val Muted = Color(0xFF9AAABD)

private val PICK_TYPES = arrayOf("application/vnd.android.package-archive", "application/zip", "*/*")

class MainActivity : ComponentActivity() {
    private lateinit var library: ApkLibrary

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = ApkLibrary(this)
        setContent { OverforApkApp(library, intent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverforApkApp(library: ApkLibrary, incomingIntent: Intent?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(library.all()) }
    var page by remember { mutableStateOf("home") }
    var message by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<ApkEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var keepOlder by remember { mutableStateOf(library.keepOlderVersions()) }
    var sendEntryId by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { library.import(uri, uri.lastPathSegment?.substringAfterLast('/')) }
                    .onSuccess {
                        entries = library.all()
                        sendEntryId = it.id
                        message = "${it.displayName} har lagts till."
                    }
                    .onFailure { message = it.message ?: "Kunde inte importera filen." }
            }
        }
    }

    LaunchedEffect(incomingIntent) {
        if (incomingIntent?.action == Intent.ACTION_SEND) {
            val uri = if (android.os.Build.VERSION.SDK_INT >= 33)
                incomingIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") (incomingIntent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            if (uri != null) {
                runCatching { library.import(uri, "delad-app.apk") }
                    .onSuccess {
                        entries = library.all(); sendEntryId = it.id; page = "send"
                        message = "Appen är importerad. Välj TV nedan och installera."
                    }
                    .onFailure { message = it.message ?: "Importen misslyckades." }
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, background = Navy, surface = Panel, onSurface = Color.White
    )) {
        Scaffold(containerColor = Navy, snackbarHost = { SnackbarHost(remember { SnackbarHostState() }) }) { padding ->
            Column(Modifier.fillMaxSize().background(Navy).padding(padding).padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SwapHoriz, null, tint = Accent, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("ÖverförAPK", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Flytta appar från mobilen till TV:n", fontSize = 12.sp, color = Muted)
                    }
                }
                when (page) {
                    "send" -> SendPage(
                        library = library,
                        entries = entries,
                        selectedId = sendEntryId,
                        onSelect = { sendEntryId = it },
                        onPickFile = { picker.launch(PICK_TYPES) },
                        onHelp = { page = "help" },
                        onMessage = { message = it }
                    )
                    "library" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Mina appar", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                            TextButton(onClick = { picker.launch(PICK_TYPES) }) {
                                Icon(Icons.Default.Add, null); Text(" Lägg till")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = keepOlder, onCheckedChange = {
                                keepOlder = it; library.setKeepOlderVersions(it)
                            })
                            Spacer(Modifier.width(8.dp))
                            Text("Behåll äldre versioner", color = Muted, fontSize = 13.sp)
                        }
                        Text("Lagring: ${formatBytes(entries.sumOf { it.file.length() })}", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        if (entries.isEmpty()) EmptyState("Ditt bibliotek är tomt", "Lägg till en APK, APKS eller XAPK från mobilen.")
                        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(entries, key = { it.id }) { entry ->
                                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(15.dp)) {
                                        Text(entry.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                        Text("${entry.versionName ?: "Okänd version"}  •  ${formatBytes(entry.file.length())}", color = Muted, fontSize = 12.sp)
                                        Text(entry.packageName ?: "Paketinformation saknas", color = Muted, fontSize = 11.sp)
                                        Spacer(Modifier.height(8.dp))
                                        Button(
                                            onClick = { sendEntryId = entry.id; page = "send" },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Icon(Icons.Default.Tv, null); Text("  Skicka till TV") }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(onClick = { renameTarget = entry; renameText = entry.displayName }) { Text("Byt namn") }
                                            TextButton(onClick = {
                                                library.delete(entry); entries = library.all()
                                                if (sendEntryId == entry.id) sendEntryId = null
                                            }) { Text("Ta bort") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "github" -> GithubPage(
                        library = library,
                        onImported = {
                            entries = library.all()
                            sendEntryId = it.id
                            message = "${it.displayName} har lagts till i biblioteket. Skicka den till TV:n via startsidan."
                        },
                        onMessage = { message = it }
                    )
                    "help" -> HelpPage()
                    else -> {
                        Text("Vad vill du göra?", color = Muted, fontSize = 14.sp)
                        Spacer(Modifier.height(14.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { page = "send" },
                            colors = CardDefaults.cardColors(containerColor = Accent),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tv, null, tint = Navy, modifier = Modifier.size(34.dp))
                                Spacer(Modifier.width(15.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Skicka app till TV", color = Navy, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                    Text("Välj APK, välj TV, installera. Tre steg.", color = Navy, fontSize = 12.sp)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = Navy)
                            }
                        }
                        HomeCard(Icons.Default.Apps, "Mina appar", "Samla och hantera APK-filer", "${entries.size} appar") { page = "library" }
                        HomeCard(Icons.Default.CloudDownload, "Från GitHub", "Hämta senaste versioner och releases") { page = "github" }
                        HomeCard(Icons.Default.Info, "Förbered TV:n", "Steg-för-steg-guide, görs en gång") { page = "help" }
                        Spacer(Modifier.height(12.dp))
                        Text("Tips: dela en APK-fil från valfri app till ÖverförAPK så hamnar du direkt i guiden.", color = Muted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (page != "home") {
                    TextButton(onClick = { page = "home" }, modifier = Modifier.align(Alignment.Start)) {
                        Icon(Icons.Default.ArrowBack, null); Text(" Till startsidan")
                    }
                }
                message?.let { msg ->
                    Text(msg, color = Accent, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Byt namn") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    library.rename(target, renameText)
                    entries = library.all()
                    renameTarget = null
                }) { Text("Spara") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Avbryt") } }
        )
    }
}

@Composable
private fun HomeCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, trailing: String? = null, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
            if (trailing != null) Text(trailing, color = Accent, fontSize = 12.sp)
            Icon(Icons.Default.ChevronRight, null, tint = Muted)
        }
    }
}

@Composable
internal fun PageTitle(title: String, subtitle: String) {
    Text(title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
    Text(subtitle, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
}

@Composable
internal fun EmptyState(title: String, subtitle: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Inventory2, null, tint = Accent, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    bytes >= 1024L -> "${"%.0f".format(bytes / 1024.0)} KB"
    else -> "$bytes B"
}
