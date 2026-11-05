# ÖverförAPK – projektets riktning och krav

Det här dokumentet samlar produktbeslut, användarbehov och tekniska krav som ska styra fortsatt utveckling av det befintliga Android-projektet. Det är ett komplement till koden, inte en ersättning för den.

## Produktmål

ÖverförAPK ska vara ett enkelt och pålitligt verktyg för att hämta, organisera och installera Android-appar från en Android-mobil till Android TV/TV-box via det lokala nätverket. Appen ska fungera utan att en dator eller separat ADB-installation behövs i det normala användarflödet.

Appen är fristående. Den ska inte byggas in i eller göras beroende av andra appar/projekt.

## Huvuddelar

1. **Mina appar** – lokalt APK-bibliotek som finns kvar på mobilen och kan användas offline.
2. **Från GitHub** – bläddra bland användarens repositories, releases och APK-assets; hämta en vald version till det lokala biblioteket.
3. **Mina TV-apparater / Skicka app till TV** – hitta eller spara TV-enheter, kontrollera installerad version och installera eller öppna vald app.
4. **Förbered TV:n** – korta, begripliga instruktioner för utvecklaralternativ, ADB och parkoppling.

## Bibliotek och filformat

- Stöd för APK samt split-arkiv i APKS- och XAPK-format.
- Import via Androids filväljare och delningsmenyn (Androids vanliga dela/till-app-flöde).
- Kopiera importerade filer till appens privata lagring så att de finns kvar offline.
- Läs appnamn, paketnamn, versionsnamn, versionCode och ikon när informationen finns tillgänglig.
- Tillåt att användaren byter visningsnamn.
- Som standard ska en nyare version med samma paketnamn ersätta den äldre. En inställning ska kunna behålla äldre versioner.
- Visa filstorlek och bibliotekets lagringsanvändning. Felaktiga eller ofullständiga arkiv ska avvisas med ett begripligt fel.
- För split-APK ska samtliga nödvändiga delar installeras som en gemensam installation.

## GitHub-flöde

- Användaren ska kunna ange sitt GitHub-konto och välja bland egna repositories.
- Visa releaser, versionshistorik och nedladdningsbara APK-assets.
- Publika repositories ska fungera utan token.
- Privata repositories ska kunna användas med en token med minsta nödvändiga läsbehörighet.
- Token får aldrig skrivas in i källkod, README, loggar eller GitHub-commits. Den ska lagras krypterad lokalt med Android Keystore.
- En nedladdad APK ska sparas i Mina appar och därefter kunna installeras offline.
- Jämför versionCode i biblioteket med installerad version på TV:n när det går att läsa av, och visa tydligt när en nyare version finns.

## TV-anslutning och installation

- Stöd både äldre ADB över nätverk (vanligen port 5555) och Androids Trådlösa felsökning med parkoppling/TLS där TV:n stöder det.
- Sök efter TV-enheter automatiskt på det lokala nätverket och erbjud manuell IP/port när automatisk upptäckt inte fungerar.
- Användaren ska kunna spara flera TV-enheter och välja målenhet före installation.
- Visa anslutningsstatus, vald app, vald TV och tydlig installationsprogress/resultat.
- Efter lyckad installation ska appen erbjuda att starta appen på TV:n.
- Uppdatering ska använda ett läge som bevarar appens data (exempelvis `pm install -r` när den äldre TV:ns ADB stöder det).
- Split-installation ska använda `install-multiple` eller motsvarande korrekt package-manager-flöde.
- Kontrollera APK:ns ABI mot TV:ns ABI när det finns native-bibliotek, och ge ett begripligt fel om de inte matchar.
- Äldre TV-boxar kan ha ADB-servrar som bryter den direkta streaming-installationen. Därför ska en alternativ väg kunna användas: överför APK-delarna till en temporär fil på TV:n och kör package manager lokalt på TV:n. Rensa temporära filer efteråt när det är möjligt.
- Felmeddelanden ska skilja på anslutningsproblem, nekad ADB-behörighet, avbruten överföring, inkompatibel APK och att Androids package manager avvisar installationen.

## UX och visuell riktning

- Svenskt språk som standard och enkel terminologi.
- Mörkt, modernt gränssnitt med marinblå bas, tydliga kort och en diskret cyan/turkos accent.
- Prioritera en tydlig stegordning: välj app → välj TV → installera.
- Startsidan ska göra de tre huvudvägarna lätta att hitta: Mina appar, Från GitHub och Mina TV-apparater.
- Status ska vara begriplig även för någon som inte känner till ADB.
- Undvik att visa tekniska detaljer i onödan; lägg felsökningsinformation i hjälpsidan eller bakom ett mer detaljerat fel.

## Tekniska principer

- Fortsätt i det befintliga Kotlin/Jetpack Compose-projektet och behåll nuvarande paketnamn och appidentitet om inte ett medvetet beslut tas.
- Håll nätverksarbete och filoperationer utanför UI-tråden.
- Hantera nätverksavbrott och timeout med tydliga fel samt begränsade återförsök där det är säkert.
- Återanvänd den befintliga ADB-implementationen och dess autentiseringsnycklar; skapa inte parallella, inkompatibla anslutningslösningar.
- Hemligheter, privata tokens och lokala enhetsuppgifter ska inte checkas in i Git.
- GitHub Actions ska bygga APK automatiskt och publicera en release med verifierbar APK-asset.
- Versionsnummer ska höjas inför varje ny publicerad release. En release ska inte kallas klar förrän Actions-jobbet lyckats och APK-filen finns bland release-assets.

## Testmål och acceptanskriterier

Testa både mot emulator och fysisk Android TV/TV-box. En lyckad kompilering räcker inte som bevis för att ADB-installation fungerar på fysisk hårdvara.

Minsta testmatris:
- Importera en vanlig APK från filväljaren.
- Dela en APK från en annan Android-app till ÖverförAPK.
- Importera och installera ett APKS/XAPK med flera delar.
- Hämta en APK från en publik GitHub-release.
- Hämta från ett privat repository med en lokalt sparad token.
- Hitta TV automatiskt och lägga till TV manuellt.
- Installera en app som inte finns på TV:n.
- Uppdatera en befintlig app och kontrollera att dess data finns kvar.
- Kontrollera installerad version och att appen kan startas från mobilen.
- Prova äldre TV-box/ADB där den direkta installationsströmmen kan brytas.
- Kontrollera att fel visas begripligt när nätverket bryts, behörighet saknas eller APK:n inte passar.

## Prioriterad fortsättning

1. Verifiera den nya reservmetoden för äldre ADB-servrar på fysisk TV-box.
2. Säkerställ att vanliga APK och split-APK fungerar genom både direkt- och reservflöde.
3. Förbättra status/progress och återkoppling under överföring.
4. Förbättra TV-listan: namn, anslutningsstatus, senast använd och enkel hantering av sparade enheter.
5. Förbättra versionsöversikten så att användaren snabbt ser installerad version jämfört med tillgänglig version.
6. Fortsätt med stabilitet, felhantering, tillgänglighet och testning innan större visuella ombyggnader.

## Integritet

Dokumentet innehåller inga privata IP-adresser, GitHub-token, ADB-nycklar eller andra lokala autentiseringsuppgifter. Sådana uppgifter ska endast finnas lokalt på användarens enhet.
