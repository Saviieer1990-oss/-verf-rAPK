package se.overforapk.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun GuideStep(number: String, title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp)) {
            Surface(color = Accent, shape = RoundedCornerShape(50), modifier = Modifier.size(26.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(number, color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(body, color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
internal fun ColumnScope.HelpPage() {
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PageTitle("Förbered TV:n", "Görs en gång. Menyernas namn kan skilja sig åt mellan tillverkare.")
        GuideStep("1", "Samma Wi-Fi", "Mobilen och TV:n ska vara på samma Wi-Fi-nätverk. Gästnät och vissa routrar med \"AP-isolering\" blockerar kontakten.")
        GuideStep("2", "Slå på utvecklarläge", "På TV:n: Inställningar > System > Om. Tryck sju gånger på \"Build\" (ibland \"Android TV OS-build\") tills det står att du är utvecklare.")
        GuideStep("3", "Slå på felsökning", "Gå till Inställningar > System > Utvecklaralternativ. Slå på \"USB-felsökning\". Finns \"Trådlös felsökning\" (Android 11 och nyare), slå på den också. På äldre TV:ar heter det \"Nätverksfelsökning\" eller \"ADB över nätverk\".")
        GuideStep("4", "Skicka appen", "Gå till \"Skicka app till TV\" i appen, välj APK-filen och TV:n. Appen söker själv efter TV:n.")
        Text("Första gången du installerar", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        GuideStep("A", "Trådlös felsökning (nyare TV)", "Appen ber om en 6-siffrig kod. På TV:n: Trådlös felsökning > \"Para enhet med parkopplingskod\". Skriv koden i appen. Appen hittar port och adress själv, och parkopplingen görs bara en gång.")
        GuideStep("B", "Port 5555 (äldre TV)", "TV:n visar frågan \"Tillåt USB-felsökning?\". Bocka i \"Tillåt alltid från den här enheten\" och välj OK. Utan den bockningen måste du godkänna igen vid varje ny anslutning. Väljer du \"Ej tillåt\" nekas anslutningen, se längst ner.")
        Text("Om det inte fungerar", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        GuideStep("•", "TV:n syns inte", "Kontrollera att TV:n inte är i viloläge och att ni är på samma Wi-Fi. Slå av och på Trådlös felsökning och tryck \"Sök igen\". Du kan också ange TV:ns IP-adress manuellt.")
        GuideStep("•", "Installationen misslyckas", "Meddelandet från TV:n visas i appen. Vanligast är att en nyare version redan finns, att appen inte passar TV:ns Android-version eller att lagringsutrymmet är fullt.")
        GuideStep("•", "TV:n svarar \"Ej tillåt\"", "Då nekas anslutningen och appen får ingen kontakt. Starta om installationen och välj \"Tillåt\" (bocka i \"Tillåt alltid\") när frågan visas på TV:n. Har du redan nekat: Inställningar > System > Utvecklaralternativ > \"Återkalla behörigheter för USB-felsökning\", och försök igen. Vid Trådlös felsökning: ta bort mobilen under Parkopplade enheter på TV:n och parkoppla på nytt.")
        GuideStep("•", "Appen passar inte TV:ns processor", "Appen kontrollerar TV:ns arkitektur före installationen. Visas ett sådant fel behöver du en annan version av APK-filen (till exempel armeabi-v7a eller en universal APK).")
        GuideStep("•", "Appen syns inte på TV:ns startsida", "Många mobilappar saknar TV-ikon. Starta dem med Öppna-knappen i appen, eller via Inställningar > Appar > Visa alla appar på TV:n.")
        Spacer(Modifier.height(8.dp))
    }
}
