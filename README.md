# ÖverförAPK

Android-app för att flytta appar (APK-filer) från mobilen till en Android TV över det lokala nätverket.

## Så använder du appen

1. **Förbered TV:n en gång** (guide finns i appen under "Förbered TV:n"): slå på utvecklarläge och felsökning (helst "Trådlös felsökning").
2. Öppna **Skicka app till TV**:
   1. Välj app, eller lägg till en APK/APKS/XAPK-fil från mobilen. Du kan också dela en APK-fil från valfri app till ÖverförAPK.
   2. Välj TV. Appen söker själv efter TV:ar på Wi-Fi (Trådlös felsökning via mDNS och ADB-porten 5555). Du kan även ange IP-adress.
   3. Tryck **Installera**.
3. Första gången med Trådlös felsökning ber appen om en 6-siffrig parkopplingskod från TV:n. Appen hittar TV:ns adress och portar själv, och parkopplingen görs bara en gång. Anslutningsporten ändras när funktionen slås av och på, men appen slår upp den på nytt vid varje installation.

## Funktioner

- Appbibliotek: importera via delning eller filväljare, split-arkiv (APKS/XAPK), byta namn, behåll äldre versioner. Filnamn läses från Androids dokumentleverantör och felaktiga arkiv avvisas.
- Från GitHub: lista repositories och hämta APK-filer från releases (publika repos utan token; privata med token som har läsrätt). GitHub-token krypteras med Android Keystore.
- Arkitekturkontroll: appen läser TV:ns processorarkitektur (ABI) och jämför med APK-filens inbyggda bibliotek innan något skickas. Passar de inte får du ett tydligt besked i stället för INSTALL_FAILED_NO_MATCHING_ABIS.
- Stabil överföring: installationen försöker först en direkt ström, återansluter om anslutningen bryts och faller sedan tillbaka på en reservmetod som skickar filen till TV:ns lagring i små bitar. Bryts anslutningen återansluter appen, läser hur mycket som kom fram och fortsätter därifrån, och kör sedan `pm install -r`. Innan filen skickas provskriver appen till TV:n (först `/data/local/tmp`, annars `/sdcard/Download`) och kontrollerar filstorleken efter varje avbrott, så att en TV som säger ok utan att spara något upptäcks direkt. Skärmen hålls tänd och Wi-Fi vaket under överföringen. Befintlig appdata behålls vid uppdatering. Om TV:n avvisar installationen visas orsaken på svenska (signatur, nyare version, lagringsutrymme med mera).
- Fast signeringsnyckel (`app/overforapk-debug.keystore`) så att nya bygge uppdaterar det förra. Nyckeln är en öppen debug-nyckel med lösenordet "android" och är inte avsedd för känsliga appar. Första bygget med den kräver att du avinstallerar tidigare version en gång.
- TV-installation via ADB utan adb-program: dadb för ADB över nätverk (port 5555) och libadb-android med Conscrypt för Trådlös felsökning (parkoppling + TLS).

## Bygg

GitHub Actions bygger debug-APK automatiskt vid push till `main` och kan köras manuellt via Actions. Beroenden hämtas från Google, Maven Central och JitPack (libadb-android, sun-security-android).
