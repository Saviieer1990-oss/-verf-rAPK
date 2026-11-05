package se.overforapk.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ColumnScope.GithubPage(
    library: ApkLibrary,
    onImported: (ApkEntry) -> Unit,
    onMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(library.githubUser()) }
    var token by remember { mutableStateOf(library.githubToken()) }
    var showSettings by remember { mutableStateOf(user.isBlank() && token.isBlank()) }
    var repos by remember { mutableStateOf<List<GhRepo>?>(null) }
    var selected by remember { mutableStateOf<GhRepo?>(null) }
    var releases by remember { mutableStateOf<List<GhRelease>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<Long?>(null) }

    fun loadRepos() {
        scope.launch {
            loading = true
            selected = null; releases = null
            runCatching { withContext(Dispatchers.IO) { GithubClient(token).repos(user) } }
                .onSuccess { repos = it; if (it.isEmpty()) onMessage("Inga repositories hittades.") }
                .onFailure { onMessage(it.message ?: "Kunde inte hämta repositories."); repos = null }
            loading = false
        }
    }

    fun openRepo(repo: GhRepo) {
        scope.launch {
            selected = repo; releases = null; loading = true
            runCatching { withContext(Dispatchers.IO) { GithubClient(token).releases(repo.fullName) } }
                .onSuccess { releases = it; if (it.isEmpty()) onMessage("Inga releases med APK-filer i ${repo.name}.") }
                .onFailure { onMessage(it.message ?: "Kunde inte hämta releases."); releases = emptyList() }
            loading = false
        }
    }

    fun download(asset: GhAsset) {
        scope.launch {
            downloading = asset.id
            runCatching {
                withContext(Dispatchers.IO) {
                    library.importStream(asset.name) { GithubClient(token).openAsset(asset) }
                }
            }.onSuccess { onImported(it) }
                .onFailure { onMessage(it.message ?: "Nedladdningen misslyckades.") }
            downloading = null
        }
    }

    // Första gången: hämta automatiskt om det finns sparade uppgifter
    LaunchedEffect(Unit) { if (!showSettings && repos == null) loadRepos() }

    PageTitle("Från GitHub", "Hämta APK-filer från releases i dina repositories.")

    if (showSettings) {
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = user, onValueChange = { user = it }, singleLine = true,
                    label = { Text("GitHub-användarnamn") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, singleLine = true,
                    label = { Text("Token (valfri, för privata repos)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Token sparas lokalt i appen. Använd en fine-grained token med enbart läsrätt (Contents: Read).",
                    color = Muted, fontSize = 11.sp
                )
                Button(
                    enabled = user.isNotBlank() || token.isNotBlank(),
                    onClick = {
                        runCatching { library.setGithub(user, token) }
                            .onSuccess { showSettings = false; loadRepos() }
                            .onFailure { onMessage("Kunde inte kryptera GitHub-tokenen: ${it.message ?: "okänt fel"}") }
                    }
                ) { Text("Spara och hämta") }
            }
        }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (token.isNotBlank() && user.isBlank()) "Inloggad med token" else "@$user",
            color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { loadRepos() }, enabled = !loading) {
            Icon(Icons.Default.Refresh, null); Text(" Uppdatera")
        }
        TextButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, null) }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))

    val repo = selected
    val rels = releases
    if (repo != null) {
        TextButton(onClick = { selected = null; releases = null }) {
            Icon(Icons.Default.ArrowBack, null); Text(" ${repo.name}")
        }
        if (rels != null && rels.isEmpty() && !loading) {
            EmptyState("Inga APK-filer", "Det finns inga releases med .apk, .apks eller .xapk i det här repot.")
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rels?.forEach { rel ->
                items(rel.assets, key = { it.id }) { asset ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(asset.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(
                                    "${rel.title}${if (rel.prerelease) " (förhandsversion)" else ""} • ${rel.published} • ${formatBytes(asset.size)}",
                                    color = Muted, fontSize = 11.sp
                                )
                            }
                            if (downloading == asset.id) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Button(enabled = downloading == null, onClick = { download(asset) }) {
                                    Icon(Icons.Default.CloudDownload, null)
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        val list = repos
        if (list != null && list.isEmpty() && !loading) EmptyState("Inga repositories", "Hittade inga repositories för kontot.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list ?: emptyList(), key = { it.fullName }) { r ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { openRepo(r) },
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (r.isPrivate) "${r.name}  🔒" else r.name,
                                color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                            )
                            r.description?.let { Text(it, color = Muted, fontSize = 11.sp, maxLines = 2) }
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Muted)
                    }
                }
            }
        }
    }
}
