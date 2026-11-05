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
- TV-installation via ADB utan adb-program: dadb för ADB över nätverk (port 5555) och libadb-android med Conscrypt för Trådlös felsökning (parkoppling + TLS).

## Bygg

GitHub Actions bygger debug-APK automatiskt vid push till `main` och kan köras manuellt via Actions. Beroenden hämtas från Google, Maven Central och JitPack (libadb-android, sun-security-android).
