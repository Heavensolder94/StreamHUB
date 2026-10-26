# StreamHub V1.5 — Android TV / Google TV

Eigenständiger Webseiten-Hub für Android-TV-Geräte mit Fernbedienung, inklusive Anime-, Film-, Serien- und **Mischportale**-Kategorien. Kodi wird nicht benötigt oder verändert.

> **Status:** Vollständiges Android-Studio-Quellprojekt. Hier **nicht als APK kompiliert** oder auf einer Xiaomi-TV-Box getestet, da im Arbeitscontainer Android SDK und Gradle fehlen. Das bestehende GitHub-Actions-Buildskript ist aktualisiert, aber noch nicht ausgeführt.

## Neu in V1.5

- Der vorhandene Platzhalter **Filmo** ist jetzt mit `https://filmo.to/` verlinkt und verbleibt unter **Filme**.
- Weiterhin **57 Startereinträge**, davon **36 mit URL** und **21 Platzhalter**. Die Adresse wurde in einem öffentlichen Webseitenindex bestätigt; die App-Wiedergabe ist nicht getestet. Filmo beschreibt sich selbst als Verzeichnis mit externen Videoplayern. Rechte und Sicherheit der konkreten Angebote sind nicht verifiziert.
- Updates ergänzen nur die noch leere und unveränderte Filmo-Kachel. Umbenannte, umsortierte oder bereits bearbeitete Einträge und Favoriten bleiben erhalten. Gelöschte Einträge werden nicht neu angelegt.

## Neu in V1.4

- Die bestehenden Platzhalter **Moflix** und **HDfilme** haben jetzt die vom Nutzer angegebenen HTTPS-Adressen `https://moflix-stream.xyz/` und `https://hdfilme.to/`. Beide bleiben unter **Mischportale**.
- Insgesamt **57 Startereinträge**, davon damals **35 mit URL** und **22 Platzhalter**. Die URLs sind als Lesezeichen gespeichert, nicht als funktionierende oder rechtmäßige Streamingquellen verifiziert.
- Beim Upgrade von V1.3 werden nur bisher unbefüllte, unveränderte Einträge aktualisiert. Selbst bearbeitete Links, Titel und Favoriten bleiben bestehen. Gelöschte Einträge werden nicht wiederhergestellt.

## Bereits seit V1.3 vorhanden

### 1. Mischportale als vierte Kategorie

- Vierte Kategorie für kombinierte Film- und Serienangebote im linken Seitenmenü, auf der Startseite und in der Webseitenverwaltung.
- Von den **57 Startereinträgen** befinden sich jetzt 11 unter Anime, 18 unter Filme, 7 unter Serien und 21 unter Mischportale.
- **36 Einträge mit HTTPS-Adresse**, 21 Platzhalter ohne ausreichend sicher zuordenbare Adresse (V1.3: 33 / 24).
- Beispiele bei Mischportalen: KinoX, FilmPalast, Cine.to, Streamkiste, Movie4k, Netflix, Prime Video, Disney+, Joyn.
- Vorhandene Daten werden bei Upgrade von V1.2 beibehalten. Unveränderte ältere Starteinträge werden in die neue Kategorie umsortiert; **eigene URLs, Favoriten und bewusst gelöschte ältere Einträge** werden nicht überschrieben oder wiederhergestellt.

### 2. Erreichbarkeitsstatus

In **Verwalten**:

- `Prüfen` neben einer Webseite testet **auf ausdrücklichen Wunsch** ihre HTTPS-Adresse; `Alle Adressen prüfen` startet nach Bestätigung eine parallele Prüfung mit maximal vier Threads.
- Status je Seite: **Noch nicht geprüft**, **Erreichbar**, **Nicht erreichbar**, **Zugriff eingeschränkt** oder **Prüfung unklar**.
- **Adresse fehlt** ist eine eigene Anzeige, kein Ausfall.
- Es wird der Zeitpunkt der letzten Prüfung gespeichert. Filter `Nur nicht erreichbar` zeigt nicht erreichbare Seiten und Einträge ohne Adresse.
- Technisch: eine HTTPS-HEAD-Anfrage (bei `405`/`501` einmal GET), 6,5 Sekunden Verbindung-/Lesetimeout. HTTP `200–399` gilt als Serverantwort; `404`/`410` oder nicht auflösbare DNS-Namen gelten als nicht erreichbar; `401`, `403`, `429` als Zugriff eingeschränkt. Andere Fehler bleiben **unklar**.
- Die App prüft **nicht automatisch im Hintergrund**, um Websites ohne dein Zutun nicht zu kontaktieren. Ein CAPTCHA, eine Sperre, HTTPS-Zertifikatprobleme oder ein Browser-/WebView-Problem lassen sich mit einer solchen Anfrage **nicht verlässlich diagnostizieren**. Ein HTTP-Redirect bestätigt nicht die Funktionsfähigkeit der Zielseite. Statuswerte sind Momentaufnahmen und **keine Garantie für legales oder sicheres Streaming**.

### 3. Webseitenlisten importieren

Unter **Verwalten → Liste importieren** kannst du per Android-Dateiauswahl eine Datei vom Gerät auswählen. Es werden unterstützt:

- **CSV:** `name,url,category`, mit Komma oder Semikolon, mit Anführungszeichen. Kategorien: `Anime`, `Filme`, `Serien`, `Mischportale`.
- **JSON:** Array mit `{ "name": "...", "url": "https://...", "category": "Anime" }`; optional `title` statt `name`, `section` statt `category` oder nur eine HTTPS-URL als String.
- **TXT:** Pro Zeile eine HTTPS-Adresse, oder `Name | https://adresse | Kategorie`. Ohne Kategorie wird `Mischportale` verwendet.

Die Dateiauswahl liest maximal **1 MiB** und akzeptiert höchstens **1.000 Einträge**. Nur HTTPS-URLs mit Host sind zulässig. Vor dem Import wird eine Übersicht gezeigt. Schon vorhandene URLs bleiben erhalten (Dublettenschutz), bestehende bearbeitete Einträge und Favoriten werden **nicht überschrieben**. Ist ein namensgleicher Platzhalter in derselben Kategorie noch ohne URL, wird nur dessen URL ergänzt.

Beispiele findest du unter `examples/webseiten-vorlage.csv` und `.json`. **Diese enthalten absichtlich nur `example.org` und `example.com` als Platzhalter**; ersetze sie vor einem echten Import durch deine gewünschten URLs.

## Schon vorhandene Funktionen

- Dark-Mode-TV-Oberfläche, Kategorien, Startseite, Suche, fernbedienungsfokussierte Buttons.
- Einzelne Webseiten hinzufügen, bearbeiten, entfernen; Favoriten und Besuchsverlauf lokal speichern.
- Webseite in Android WebView öffnen, vor/zurück, neuladen und alternativ einen externen Browser verwenden.
- HTML5-Vollbildvideo, sofern Website, Android WebView und Videoplayer das unterstützen.
- Keine Kodi-Abhängigkeit, keine Stream-Resolver, kein DRM-Bypass.

## Upgrade und lokaler Speicher

- Android-Paketkennung bleibt **`de.streamhub.tv`**, Versionsnummer ist jetzt **1.5.0** (`versionCode = 6`).
- App-Updates mit **derselben Signatur** erhalten gespeicherte Webseiten, Verlauf, Favoriten und Status. Die Debug-APK ist für Tests gedacht; unterschiedliche Signierschlüssel können ein direktes Update verhindern.
- V1.0/V1.1/V1.2/V1.3/V1.4-Speicherstände werden beim ersten Start automatisch migriert. Vorhandene Einträge mit eigenen URLs/Änderungen werden nicht überschrieben. Bei älteren Standardeinträgen ohne Änderung kann ihre Kategorie automatisch auf „Mischportale“ wechseln.

## APK bauen

### GitHub Actions

1. Inhalt des Projektordners in ein GitHub-Repository kopieren (gerne privat).
2. Reiter **Actions → Build Android TV APK → Run workflow**.
3. Unter **Artifacts** das Ergebnis **`StreamHub-V1.5-debug-apk`** herunterladen.
4. APK auf die Xiaomi-TV-Box übertragen und installieren. Beim ersten Mal ggf. Installieren aus unbekannten Quellen erlauben.

### Android Studio

- Ordner `StreamHub-V1.5` öffnen und Gradle synchronisieren.
- Erforderlich: JDK 17, Android SDK 35, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Gradle 8.9.
- **Build → Build Bundle(s) / APK(s) → Build APK(s)**; erwartet unter `app/build/outputs/apk/debug/app-debug.apk`.
- Der **Gradle Wrapper ist nicht im ZIP enthalten**. Android Studio kann eine lokale Gradle-Installation bzw. den Wrapper einrichten. Der GitHub-Workflow installiert Gradle selbst.

## Rechtliche und technische Grenzen

StreamHub stellt **Links** bereit, hostet keine Filme oder Serien und bietet keine Möglichkeit, Kodi-Add-ons unverändert auszuführen. Bei manchen inoffiziellen Portalen können urheberrechtliche Nutzungsrechte fehlen; die Aufnahme als Bookmark ist keine Bestätigung der Legalität, Funktion oder Sicherheit. In WebView funktionieren einige Logins, DRM-Player und Webseiten nicht zuverlässig. Bei häufig wechselnden Domains sollten Adressen bewusst verifiziert werden, statt unbekannten Spiegel-Domains zu vertrauen.

## Code-Übersicht

- `SiteStore.kt`: Katalog, Favoriten, Verlauf, Migration, Statusfelder, Dubletten-schonender Import.
- `SiteListImporter.kt`: CSV-/JSON-/TXT-Parser und HTTPS-Validierung.
- `SiteChecker.kt`: ausdrücklich ausgelöste, begrenzte HTTPS-Statusanfragen.
- `MainActivity.kt`: TV-Layout, Kategorie Mischportale, Verwalten, Status, Dateiauswahl und Import-Vorschau.
- `BrowserActivity.kt`: interner Browser mit HTML5-Vollbild.
- `.github/workflows/android.yml`: Debug-APK-Build mit Gradle 8.9 und Android SDK 35.

© 2026 StreamHub-Prototyp. Unabhängig von Kodi und allen gelisteten Drittanbietern.
