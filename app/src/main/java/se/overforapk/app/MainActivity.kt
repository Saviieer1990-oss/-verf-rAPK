package se.overforapk.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
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

private val Navy = Color(0xFF0A111B)
private val Panel = Color(0xFF142233)
private val Accent = Color(0xFF55C4D7)
private val Muted = Color(0xFF9AAABD)

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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val result = runCatching {
                    library.import(uri, uri.lastPathSegment?.substringAfterLast('/') )
                }
                result.onSuccess {
                    entries = library.all()
                    message = "${it.displayName} har lagts till i biblioteket."
                }.onFailure { message = it.message ?: "Kunde inte importera filen." }
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
                    .onSuccess { entries = library.all(); page = "library"; message = "Appen har importerats." }
                    .onFailure { message = it.message ?: "Importen misslyckades." }
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, background = Navy, surface = Panel, onSurface = Color.White
    )) {
        Scaffold(containerColor = Navy, snackbarHost = { SnackbarHost(remember { SnackbarHostState() }) }) { padding ->
            Column(Modifier.fillMaxSize().background(Navy).padding(padding).padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SwapHoriz, null, tint = Accent, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("ÖverförAPK", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Ditt appbibliotek. Dina enheter.", fontSize = 12.sp, color = Muted)
                    }
                }
                when (page) {
                    "library" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Mina appar", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                            TextButton(onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "*/*")) }) {
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
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(entries, key = { it.id }) { entry ->
                                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(15.dp)) {
                                        Text(entry.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                        Text("${entry.versionName ?: "Okänd version"}  •  ${formatBytes(entry.file.length())}", color = Muted, fontSize = 12.sp)
                                        Text(entry.packageName ?: "Paketinformation saknas", color = Muted, fontSize = 11.sp)
                                        Spacer(Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = { renameTarget = entry; renameText = entry.displayName }) { Text("Byt namn") }
                                            OutlinedButton(onClick = {
                                                library.delete(entry); entries = library.all()
                                            }) { Text("Ta bort") }
                                            Button(onClick = { message = "TV-installation är nästa utvecklingssteg och är ännu inte aktiverad." }) { Text("Installera") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "github" -> {
                        PageTitle("Från GitHub", "Hämta APK-filer från dina egna repositories.")
                        EmptyState("GitHub-koppling kommer", "Nästa steg är att visa repositories, releases och APK-filer samt spara dem offline.")
                    }
                    "devices" -> {
                        PageTitle("Mina TV-apparater", "Samla dina Android TV-enheter på ett ställe.")
                        EmptyState("Ingen TV tillagd ännu", "Enhetsupptäckt och ADB-anslutning byggs i nästa steg.")
                    }
                    else -> {
                        Text("Välj vad du vill göra", color = Muted, fontSize = 14.sp)
                        Spacer(Modifier.height(14.dp))
                        HomeCard(Icons.Default.Apps, "Mina appar", "Samla och hantera APK-filer", "${entries.size} appar") { page = "library" }
                        HomeCard(Icons.Default.CloudDownload, "Från GitHub", "Hämta senaste versioner och releases") { page = "github" }
                        HomeCard(Icons.Default.Tv, "Mina TV-apparater", "Installera appar via ditt Wi-Fi") { page = "devices" }
                        Spacer(Modifier.height(20.dp))
                        Text("Biblioteket sparas lokalt på mobilen och kan användas offline.", color = Muted, fontSize = 12.sp)
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
private fun PageTitle(title: String, subtitle: String) {
    Text(title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
    Text(subtitle, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Inventory2, null, tint = Accent, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    bytes >= 1024L -> "${"%.0f".format(bytes / 1024.0)} KB"
    else -> "$bytes B"
}
