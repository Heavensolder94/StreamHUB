# StreamHUB – In-App Updates über GitHub

StreamHUB sucht beim App-Start höchstens alle zwölf Stunden nach einem neuen **veröffentlichten GitHub Release**. Unter **Verwalten → Nach App-Updates suchen** ist eine manuelle Prüfung jederzeit möglich.

Bei einem neuen Release mit höherem Android `versionCode` zeigt die App einen Update-Dialog. Die APK wird innerhalb der App heruntergeladen, gegen den von GitHub angegebenen SHA-256-Digest geprüft und an den Android-Paketinstaller übergeben. Android verlangt normalerweise eine Installationsbestätigung; unbeaufsichtigte Silent-Updates sind nicht vorgesehen.

## Einmalig auf GitHub einrichten

Die bisherigen `assembleDebug`-APKs eignen sich **nicht** für fortlaufende Installationen über GitHub Actions: Die Debug-Signatur auf wechselnden GitHub-Runnern ist nicht stabil. Verwende für neue Releases unbedingt **einen dauerhaft gleichen privaten Release-Key**. Den Key niemals im Repository veröffentlichen.

1. Auf einem vertrauenswürdigen Rechner mit JDK einen Keystore erstellen:

   ```bash
   keytool -genkeypair -v -keystore streamhub-release.jks -alias streamhub -keyalg RSA -keysize 3072 -validity 10000
   ```

   Sichere die Datei und die Kennwörter offline. Der Verlust des Schlüssels verhindert reguläre Updates aller mit ihm signierten Installationen.

2. Den Keystore in einen Base64-String umwandeln. In **PowerShell**:

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path "./streamhub-release.jks"))) | Set-Clipboard
   ```

3. In **GitHub → Repository → Settings → Secrets and variables → Actions → Secrets** diese vier Repository-Secrets anlegen:

   - `STREAMHUB_KEYSTORE_BASE64` – der Base64-String aus Schritt 2
   - `STREAMHUB_STORE_PASSWORD` – das Keystore-Kennwort
   - `STREAMHUB_KEY_ALIAS` – `streamhub` (oder der bei der Erstellung gewählte Alias)
   - `STREAMHUB_KEY_PASSWORD` – das Schlüssel-Kennwort

4. Unter **Actions → Variables** die Repository-Variable `STREAMHUB_AUTO_RELEASE` mit Wert `true` anlegen.

5. Einen Push auf `main` auslösen oder unter **Actions → Publish StreamHUB updates → Run workflow** den Release-Workflow starten. Der Build veröffentlicht danach eine **signierte** APK als GitHub Release. Bei jedem späteren Push auf `main` wird bei erfolgreichem Build erneut ein Release veröffentlicht.

**Wichtig:** Erst Secrets anlegen und ihren Zugriff testen, dann `STREAMHUB_AUTO_RELEASE=true` setzen. So werden nicht versehentlich fehlgeschlagene Release-Jobs ausgelöst.

## Android-TV Installation

- Für Updates muss die neue APK **dieselbe Application-ID und Signatur** wie die bereits installierte App haben. Die Updateversion muss einen höheren `versionCode` haben. Die GitHub-Releases nutzen dafür den monotonen Wert `100000 + GITHUB_RUN_NUMBER`.
- **Einmaliger Wechsel von einer bisherigen Debug-APK:** Weil die neue Release-Signatur abweicht, muss die bisherige Debug-App vor der ersten signierten Release-Installation deinstalliert werden. Dabei gehen lokale App-Daten verloren – wichtige Webseiten, Portalprofile und Einstellungen vorher sichern bzw. notieren. Danach sind Updates ohne Neuinstallation möglich.
- Beim ersten In-App-Update ggf. auf dem Android-TV-Gerät einmalig **Apps aus dieser Quelle zulassen** für StreamHUB aktivieren. Anschließend die Update-Aktion erneut aufrufen.
- Android startet den Paketinstaller, der Nutzer bestätigt die Installation. Kein Root und kein Kodi erforderlich.

## Veröffentlichungsdetails

- Die Android-Paket-ID bleibt `de.streamhub.tv`.
- Der Release-Workflow erzeugt APK-Dateinamen wie `StreamHUB-v2.7.9-vc100009.apk`. Der Updater vergleicht den `vc...`-Wert mit dem installierten `versionCode`.
- Nur normale, veröffentlichte Releases mit valider SHA-256-Angabe werden angeboten; Entwürfe und Prereleases werden ignoriert.
- Ein GitHub-Commit ist **nicht** direkt ein App-Update. Erst der erfolgreich durchgelaufene signierte Release-Build erzeugt eine installierbare Version.
- Ohne eingerichtete GitHub-Secrets baut die bestehende `android.yml` weiterhin Debug-APKs als GitHub Actions Artifacts. Sie lösen jedoch keine In-App-Updates aus.

## Initialen Release-Build auslösen

Der erste veröffentlichte Release-Build wird durch einen Commit nach der Aktivierung der GitHub-Actions-Variable gestartet. Danach muss der Workflow **Publish StreamHUB updates** erfolgreich sein, bevor die APK unter **Releases** verfügbar wird.
