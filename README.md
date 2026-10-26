# StreamHUB Premium V2.5 — MAC-IPTV / Stalker-Portal

Der vorhandene native LIVE-TV-Bereich hat zusätzlich den Button 📡 MAC IPTV. Dieser öffnet einen Kodi-unabhängigen Client für vom Nutzer bereitgestellte, autorisierte MAG-/Stalker-/Ministra-Portale. Der Rest der App bleibt unverändert.

## Einrichtung

1. In LIVE TV auf 📡 MAC IPTV und anschließend ⚙ Portal gehen.
2. Die Portal-URL des eigenen IPTV-Anbieters (z. B. https://portal.example.org/c/) und die bei diesem Anbieter freigeschaltete MAC-Adresse (z. B. 00:1A:79:12:34:56) eingeben.
3. Für unverschlüsselte HTTP-Portale muss "Unsicheres HTTP ausdrücklich erlauben" aktiviert werden. Dabei ist die MAC im Netzwerk lesbar; HTTPS ist sicherer.
4. Speichern. Der Client testet ein MAG-Handshake, lädt die Kategorien und zeigt die jeweils vom Anbieter bereitgestellten Inhalte an.
5. Oben zwischen LIVE TV / FILME / SERIEN wechseln. Kategorie auswählen, Eintrag öffnen und direkt über Media3/ExoPlayer abspielen. Einige Serienportale unterstützen Episodenlisten; Varianten ohne unterstützten Episoden-Endpunkt zeigen eine Fehlermeldung.

Technische Details: Stalker-Portalsuche (portal.php, server/load.php), MAC-Cookie und Bearer-Handshake, Genre-/VOD-/Serienkategorien, seitenweise Kataloganzeige, create_link-Auflösung für zeitlich begrenzte Streams, direkter Android-TV-Player. Portal-URL und MAC werden mit Android Keystore AES-GCM lokal verschlüsselt gespeichert. Es werden keine vorinstallierten Sender, Portale oder Zugangsdaten mitgeliefert. Es findet kein Umgehen von gesperrten Konten oder Zugangsprüfungen statt.

Einschränkungen: Stalker-/Ministra-Portale unterscheiden sich in Aufbau, Gerätebindung, Tokens und Episodenformaten. Die Funktionen können ohne einen Testzugang nicht für jedes Portal verifiziert werden. HTTP wurde nur für explizit freigegebene Portale/Streams in dieser neuen Funktion vorgesehen; die Android-Netzwerkkonfiguration erlaubt dazu technisch HTTP-Anfragen appweit. Die bestehenden M3U-Importe bleiben auf HTTPS beschränkt. Es gibt noch keine EPG-/Timeshift-Funktion für MAC-Portale und keine Unterstützung zusätzlicher Hardware-Signaturen. Nur vom Anbieter legitimierte MAC-IDs verwenden.

APK: GitHub Actions → Build Android TV APK → erfolgreicher Lauf → Artifacts → StreamHub-Premium-V2.5-debug-apk.

---

# StreamHUB Premium V2.4 — Eigenständiges LIVE TV (ohne Kodi)

Diese Version bringt einen eigenen Menüpunkt **📺 LIVE TV** in StreamHUB, sowohl auf der Startseite als auch in der Film-Bibliothek. Die Senderliste und die Wiedergabe sind native Android-TV-Funktionen: **Kodi wird weder gestartet noch benötigt**.

## Was funktioniert

- Ein nativer Player auf Basis von **AndroidX Media3 / ExoPlayer** mit HLS (.m3u8), DASH (.mpd) und geeigneten direkten Mediendateien. Play/Pause, TV-Fernbedienung und Buttons zum Wechseln auf den nächsten/vorherigen Sender.
- **Eigene Senderlisten:** HTTPS-M3U-Playlist per URL oder über Androids Dateiauswahl importieren. Sender mit direkter HTTPS-Stream-Adresse einzeln hinzufügen. Doppelte URLs werden nicht erneut hinzugefügt; bestehende Favoriten bleiben erhalten.
- Kategorien aus **group-title**, Favoriten, Namenssuche, Seitenblättern bei größeren Listen, Sender löschen, alle Sender löschen.
- **Offizielle Webseiten-Shortcuts** zu ARD Live, ZDF Live, Pluto TV und Joyn Live. Diese öffnen den bestehenden StreamHUB-Webbrowser und keine direkten Streams. WebView-DRM-/Cookie-/Geoblocking-Beschränkungen gelten weiterhin.

## Was nicht vorgetäuscht wird

- xVAULT ist ein Python-Addon für Kodi. Sein LIVE-TV-Modul enthält Kodi-abhängige Senderauflösung und teils nicht verifizierte Anbieterquellen. **Es wurde nicht als StreamHUB-Katalog übernommen.** Die native StreamHUB-Version arbeitet unabhängig davon mit Nutzerdaten aus M3U-Listen.
- Es gibt absichtlich **keine vorinstallierten frei erfundenen oder ungeprüften M3U-Senderstreams**. Ein echtes Senderbild ist erst sichtbar, nachdem du eine gültige, nutzbare Stream-URL eingerichtet hast.
- XMLTV/EPG, xVAULT-Senderkatalog-Synchronisation, Catch-up TV, DRM-Login bei Bezahlsendern und automatische Titelfreischaltung sind noch nicht implementiert.
- Playlists und Stream-Adressen müssen in V2.4 **HTTPS** verwenden. Abweichende HTTP/IPTV-LAN-Streams werden aus Sicherheitsgründen nicht geladen. Der Playlist-Download ist auf 1,8 MB, 10 Sekunden Lese-Timeout und 650 Sender begrenzt.
- Sender und Favoriten werden lokal auf der Android-TV-Box gespeichert, kein Kodi, keine Server-Accounts und keine Backups aus StreamHUB ins Internet. Bei einer Deinstallation gehen lokale Daten verloren.
- Für die eigentliche Wiedergabe sind ein funktionierender Senderstream, unterstützte Codecs und gegebenenfalls die Rechte des Anbieters erforderlich. Der APK-Build prüft nicht reale Stream-Verfügbarkeit. Nutze nur Senderangebote, auf die du berechtigt zugreifen darfst.

## Installation

In GitHub Actions die erfolgreiche Aktion **Build Android TV APK** öffnen → Artifacts → **StreamHub-Premium-V2.4-debug-apk** → ZIP entpacken → `app-debug.apk` installieren. Die alten Film-/Serien-/Anime-Funktionen einschließlich Kodi/xVAULT als optionalem **Filmplayer** bleiben erhalten; **LIVE TV** öffnet Kodi nicht.

---

# StreamHUB Premium V2.3 — Alle Quellen pro Film verbinden

**Neuer Ablauf:** Film oder Serie in der TMDB-Bibliothek auswählen → „Jetzt ansehen“ → DeVideoSRC, Kodi/xVAULT, TMDB/JustWatch-Angebote und **gezielte Seitensuche auf den eigenen Webseiten** an einem gemeinsamen Ort.

## Neue „Alle Quellen“-Ansicht

- Alle **58** ursprünglich hinterlegten Einträge (ggf. plus persönliche Ergänzungen oder minus bewusst gelöschte Quellen) direkt unter einem ausgewählten Film/Serientitel zugänglich.
- **37** Einträge mit URLs im ursprünglichen Katalog (einschließlich DeVideoSRC), **21** ohne Adresse; tatsächliche Zahlen werden zur Laufzeit aus den gespeicherten Einträgen ermittelt.
- TV-optimierte Filter: Alle, Anime, Filme, Serien, Mischportale, Favoriten, Ohne Adresse sowie Namensfilter.
- Website-Eintrag öffnen: „Titel suchen“, „Startseite öffnen“, „Webadresse bearbeiten“, „Favorit ändern“, „Titel kopieren“.
- Unter „Ohne Adresse“ lassen sich fehlende URLs einzeln vom Nutzer ergänzen. Keine Fantasiedomains oder automatische Wiederherstellung absichtlich gelöschter Quellen.
- Schnellwahl direkt auf der Detailseite führt nun zu einer **titelbezogenen Suche** statt nur zur Homepage.
- Gesicherte URL-Aufbereitung mit HTTPS und Domain-Validierung; vorhandene Benutzerdaten und Einstellungen bleiben erhalten.

## Suchweg und Grenzen

Die meisten externen Webseiten bieten **keine einheitliche dokumentierte API**. Für sie öffnet StreamHUB deshalb eine **Google-Suche mit dem offiziellen site:-Operator**, begrenzt auf die Domain der gewählten Quelle und den aktuellen Filmtitel. Für YouTube nutzt StreamHUB stattdessen dessen reguläre Suchoberfläche. Beispiel: site:example.com "Interstellar". Das ist eine Suchhilfe und kein automatischer Provider-Check: **ein Suchergebnis ist kein Nachweis für einen vorhandenen oder legalen Stream**. Bei nicht indexierten Webseiten oder in WebViews eingeschränkten Suchmaschinen können Ergebnisse fehlen.

Bei den 21 leeren URL-Einträgen lässt sich nichts öffnen, bevor eine reale Adresse eingetragen ist. Auch gespeicherte Websites mit HTTPS-Adresse sind nicht automatisch erreichbar, lizenziert oder streamfähig.

**Nur TMDB-/JustWatch-Verfügbarkeitsdaten für Deutschland** zeigen konkrete Anbieter, getrennt nach Abo, kostenlos, Werbung, Miete, Kauf, und verlinken zur TMDB-Watch-Seite. DeVideoSRC und Kodi/xVAULT bleiben als separate Player über IMDb-/TMDB-ID integriert. Es wird keine DRM-Umgehung oder automatische Videolink-Extraktion umgesetzt.

## APK / Installation

GitHub → Actions → Build Android TV APK → erfolgreicher Build → Artefakt StreamHub-Premium-V2.3-debug-apk → ZIP entpacken → app-debug.apk installieren. Für TMDB-Poster und Inhaltslisten ist weiterhin ein persönlicher TMDB-Key erforderlich. Die Integration der Website-Suche kann erst mit realem Android-TV-WebView und dem jeweiligen Anbieter getestet werden.

---

# StreamHUB Premium V2.2 – optionale Kodi/xVAULT-Brücke

Diese Version ergänzt die eigenständige Android-TV-Filmbibliothek um einen separaten, optionalen **Kodi-Player über xVAULT**. Die APK enthält keine xVAULT-Python-Scraper und installiert xVAULT nicht: xVAULT bleibt im dafür vorgesehenen Kodi-Laufzeitsystem. Das Addon in der bereitgestellten ZIP deklariert GPL-3.0-only und verfügt über eigene Updates/Telemetrie/Synchronisation.

## Warum nicht direkt einbauen?

Das xVAULT-Addon ist ein Kodi-Python-Plugin (addon id: plugin.video.xvault). Es verwendet Kodi-spezifische Module, den Kodi-Player und zusätzliche Abhängigkeiten (u. a. ResolveURL und InputStream). Der Code kann daher nicht unverändert als eigenständige Kotlin-Android-App laufen.

## Einrichtung auf derselben Android-TV-Box

1. Kodi installieren und starten. xVAULT im Kodi-Addon-Menü separat aus der bereits vorhandenen ZIP oder einer vom Nutzer vertrauten Quelle installieren und aktivieren. StreamHUB installiert xVAULT nicht automatisch.
2. In Kodi: **Einstellungen → Dienste → Steuerung → Fernsteuerung über HTTP zulassen**. Authentifizierung aktiv lassen, sicheren Benutzernamen und Passwort einstellen, den HTTP-Port merken (meist 8080). Den Kodi-HTTP-Port **nicht zum Internet weiterleiten** und keinen öffentlichen Remote-Zugriff einrichten. Für diesen Connector ist nur die Loopback-Adresse 127.0.0.1 auf **demselben Gerät** erreichbar.
3. Kodi geöffnet lassen, bis der HTTP-Server läuft. StreamHUB → Film-Bibliothek → Titel auswählen → „Jetzt ansehen“ → „Player & Kodi/xVAULT“ → **Einrichten**; dort Port, Benutzer und Passwort eintragen. Einstellungen werden nur lokal auf dem Gerät gespeichert; das Passwort ist über den Android Keystore AES-GCM verschlüsselt.
4. **Verbindung testen** prüft über Kodi-JSON-RPC, ob xVAULT vorhanden und aktiviert ist.
5. **Über Kodi / xVAULT** übergibt den gewählten Film anhand der TMDB-ID, des Titels und optional der IMDb-ID. Bei einer Serie fragt StreamHUB zuerst Staffel und Episode ab. Die Übergabe verwendet die im geprüften xVAULT-Paket vorhandene TMDbHelper-kompatible Route `playTMDbHelper` über die dokumentierte Kodi-RPC-Methode `Addons.ExecuteAddon`. Danach öffnet StreamHUB Kodi, wo xVAULT seine eigene Quellenwahl durchführt.

## Grenzen & Sicherheit

- **Kein echtes In-App-xVAULT-Streaming:** Die Wiedergabe und Dialoge finden in Kodi statt. StreamHUB bleibt eigenständig; kein zweiter Filmdatenkatalog ist nötig. Für später wäre eine weitergehende, explizite Bridge nötig.
- Der Webserver muss in Kodi laufen. Android kann Kodi im Hintergrund beenden. Die Kodi-HTTP- und Plugin-Kompatibilität kann erst beim Test auf dem konkreten Android-TV-Gerät nachgewiesen werden. Ein erfolgreiches APK-Compile ist kein Nachweis des Wiedergabeflusses.
- Nur `127.0.0.1` wird angerufen; Authentifizierung und begrenzte Response-Größe/Timeouts, kein konfigurierbarer Remote-Host. Eine Android-Network-Security-Config erlaubt HTTP nur zu localhost / 127.0.0.1 und blockiert unsicheres HTTP sonst.
- Nutzerdaten/Favoriten von Kodi/xVAULT werden **nicht automatisch** in StreamHUB kopiert oder synchronisiert. Es gibt keinerlei Zwang zu Drittanbieter-Synchronisation. Auf xVAULTs eigene Update-/Telemetrie-Einstellungen achten.
- Es werden **keine Links automatisch extrahiert, keine Hosterrechte geprüft und keine Schutzmaßnahmen umgangen**. Der Benutzer ist verantwortlich, geeignete legale Quellen zu wählen.
- Die ZIP-Datei wird **nicht redistribuiert**; xVAULT muss in Kodi separat installiert sein.
- Bei Installation als Update bitte beachten, dass GitHub-Actions-Debug-Builds einen anderen Signaturschlüssel haben können. Vor Deinstallation lokale Merkliste/Settings beachten.

**Download:** GitHub Actions → Build Android TV APK → Artifacts → StreamHub-Premium-V2.2-debug-apk → app-debug.apk.

---

# StreamHUB Premium V2.1 – Film auswählen, Anbieter auswählen

**Neu:** Auf jeder Film- und Serien-Detailseite erscheinen unter dem Poster drei Quellenbereiche.

1. **DeVideoSRC-Player** bei gültiger IMDb-ID aus TMDB: Filme direkt oder über Custom-Player öffnen; Serien-Player oder einzelne Staffel und Episode wählen. Die tatsächliche Abspielbarkeit bleibt unbestätigt.
2. **Für Deutschland gemeldete Streaminganbieter:** Die dokumentierte TMDB API liefert unter /movie/{id}/watch/providers und /tv/{id}/watch/providers Verfügbarkeitsdaten aus der JustWatch-Partnerschaft. StreamHUB verwendet Ergebnisse für DE und trennt Abonnement, kostenlos, werbefinanziert, Leihe und Kauf. Die jeweiligen Logo-Schaltflächen öffnen die TMDB-Watch-Seite. **Keine direkten Stream-Links** werden von der Provider-API geliefert. Attribution: **Watch availability data provided by JustWatch via TMDB.**
3. **Gespeicherte Webseiten:** Einträge mit HTTPS-Adresse werden als Favoriten und in einer vollständigen Auswahlliste angezeigt. Beim Öffnen der Webseite wird der gewählte Filmtitel in die Zwischenablage kopiert, damit die Suche auf der Webseite leichter ist. **Keine automatische Bestätigung, dass ein Titel auf einer gespeicherten Webseite verfügbar ist.**

Die Schaltfläche **„Jetzt ansehen“** springt zum neuen Panel. Der bestehende TMDB-Katalog, eigene Merkliste, DeVideoSRC, Webseiten-Favoriten und Suchansicht bleiben erhalten.

**Installation:** TMDB v3-API-Key oder Read Access Token wird weiterhin benötigt, damit Poster, IMDb-Zuordnung und die DE-Verfügbarkeitsliste geladen werden können. GitHub Actions > Build Android TV APK > Artifacts > StreamHub-Premium-V2.1-debug-apk > ZIP entpacken > app-debug.apk.

**Wichtig:** Es gibt weiterhin keine gemeinsame technische Titel-API für alle 58 gespeicherten Webseiten. Der Player und die Drittanbieter müssen auf Android TV getestet werden. TMDB/JustWatch-Daten können sich ändern und sind keine garantierte Abspielbarkeit. Kein DRM-Bypass, kein automatisches Auslesen von Filmdateien.

---

# StreamHUB Premium V2.0 — Film- und Serienbibliothek

## Neu
- **Eigenständige Bibliothek** aus dem Hauptmenü und Hero der TV-App.
- Echte Film- und Serienposter, Backdrops, deutsche Titel/Beschreibungen und Bewertungen über die offizielle **TMDB API** und TMDB Bild-CDN. Keine erfundenen Poster.
- **Startseite:** Kino-Highlights dieser Woche, beliebte Serien, Anime-Kategorie (japanische Animationsserien). **Filme**, **Serien**, **Anime**, **Suche**, **Meine Liste** als TV-optimierte Ansichten.
- **Detailseiten:** Original-Backdrop, Handlung, Erscheinungsjahr, TMDB-Bewertung, Laufzeit/Genre (soweit vorhanden), IMDb-Zuordnung aus TMDB, Merkliste und Wiedergabeoptionen.
- **Player-Auswahl:** Bei hinterlegter IMDb-ID DeVideoSRC Standard-/Custom-Player für Filme, Serienplayer oder direkte Serienepisode. Alternativ jede bereits gespeicherte Webseite nur als **Link zur Homepage** oder TMDB-Titelseite. Der Player wird nicht zur Garantie für einen verfügbaren/lizenzierten Stream.
- **Merkliste** mit bis zu 200 lokalen Einträgen. Kein Konto, Cloud-Sync oder Nutzertracking; unabhängig von der alten Webseiten-Favoritenliste.
- **TMDB API-Key** (32 Zeichen v3) oder **Read Access Token** kann in der App eingegeben werden. Kein Schlüssel wird ins GitHub-Repository eingebaut oder benötigt. Auf dem Gerät in app-privaten SharedPreferences gespeichert (nicht speziell verschlüsselt, also nur private TV-Box verwenden).
- Bilder werden nur vom offiziellen TMDB CDN bezogen, mit Größenprüfung und begrenztem RAM-Cache. API-Anfragen laufen über HTTPS mit begrenzter Laufzeit und Antwortgröße.
- **Credits** enthalten das offizielle TMDB-Logo und den vorgeschriebenen Wortlaut: "This product uses the TMDB API but is not endorsed or certified by TMDB."

## Ersteinrichtung
1. Im eigenen TMDB-Konto auf https://www.themoviedb.org/settings/api einen persönlichen API-Key/Read Access Token erstellen. Konditionen und TMDB-Nutzungsrechte beachten (kostenlos im nichtkommerziellen Rahmen mit Attribution).
2. StreamHUB öffnen → **FILM-BIBLIOTHEK** → **TMDB API-Key einrichten** → Key/Token einfügen → speichern. Danach lädt die App echte Titel automatisch.
3. Film- oder Serienposter wählen → Detailseite → Player wählen. Für einen DeVideoSRC-Player braucht der Titel eine IMDb-ID bei TMDB. Fehlt sie, wird nur die Webseiten-/TMDB-Auswahl angeboten.
4. Android-APK: GitHub → Actions → Build Android TV APK → erfolgreicher Lauf → Artifacts → **StreamHub-Premium-V2.0-debug-apk** → ZIP entpacken → **app-debug.apk** auf Android-TV installieren.

## Hinweise
- Die App arbeitet **ohne eingebauten TMDB-Schlüssel**. Ohne eigenen Schlüssel bleibt der Katalog im Einrichtungsbildschirm. Das ist absichtlich so, damit kein privater API-Schlüssel im öffentlichen Repo landet.
- Eine TMDB-Liste liefert **Metadaten, nicht Filmdateien oder Streamingrechte**. Nicht jeder Titel hat eine IMDb-ID. Wiedergabe und Remote-/Fullscreen-Support von Drittanbietern sind auf der Xiaomi-TV-Box noch **nicht live getestet**.
- TMDB verlangt im Produktionsbetrieb eine korrekte Attribution/Logo. Logo wird im Credits-Fenster von der offiziellen TMDB-Seite geladen; es kann offline fehlen.
- Die Debug-APK ist nur für private Funktionstests; bei anderem Debug-Signierschlüssel kann Android für ein Update eine Neuinstallation verlangen (dabei lokale Daten sichern).

---

# StreamHub Premium V1.9 — Erweiterte DeVideoSRC-Integration

- Dedizierter Android-TV-Player mit iframe-Integration, Play/Pause/Seek, Info zur Quelle und lokal gespeichertem Wiedergabefortschritt.
- Dokumentierte postMessage-Befehle: play, pause, seek, getCurrentTime, getDuration, getPaused und getSource.
- Player-Ereignisse: akzeptiert nur Nachrichten von devideosrc.co; Fehler, Status und Wiedergabezeit werden soweit lesbar angezeigt. Das genaue Event-Payload-Schema ist nicht öffentlich beschrieben und muss am Gerät getestet werden.
- Player-Optionen: autoplay, muted, controls, lastserver und StreamHUB-Akzentfarbe. Direkte Episodenwahl bleibt erhalten.
- Experimentell: JSON-Abfragen /api/providers und /api/resolve?id=IMDb-ID über manuelle Buttons, ohne automatische Stream-Extraktion.
- Einschränkung: Die Dokumentation verrät nicht das erwartete Query-Format und Antwortschema von /api/resolve. Die verwendete id-Abfrage ist eine unbestätigte Annahme. Bei Fehlern zeigt die App den HTTP-Status.
- Die TV-Kompatibilität, Wiedergabe, Ereignisfelder und Hoster wurden nicht unabhängig überprüft; der GitHub-APK-Build kontrolliert nur die Android-Kompilierung.
- GitHub Actions: Build Android TV APK → Artifacts → StreamHub-Premium-V1.9-debug-apk → app-debug.apk.
- Ohne DRM-Umgehung, keine Video-URL-Extraktion, keine Zusicherung der Lizenzsituation von Inhalten externer Hoster.

---

# StreamHub Premium V1.8 — DeVideoSRC Player (Android TV)

- **Neuer Menüpunkt „DeVideoSRC“** und Schnellzugriff auf der Startseite.
- Film per **IMDb-ID** (z. B. `tt1234567`) oder numerischer **DeVideoSRC-ID** im eingebetteten Android-WebView-Player öffnen.
- **Serien** über `/serial/{id}` mit Staffel-/Folgenauswahl des Drittanbieters; **direkter Episodenstart** über `/custom/serial/{id}/{staffel}/{folge}`.
- Optionaler **Custom-Filmplayer** in StreamHub-Akzentfarbe, standardmäßig ohne Autoplay. Eingaben werden geprüft.
- **Zuletzt gestartet:** bis zu zehn Einträge nur lokal gespeichert, mit Titelangabe, Wiederöffnen und Löschen. Dies ist **keine** automatische Wiedergabepositions-Erkennung.
- **DeVideoSRC im Webseitenkatalog:** auf bestehenden Installationen ergänzt, ohne individuelle Favoriten, Links oder Änderungen zurückzusetzen.
- **Sidebar scrollbar**, damit der neue Menüpunkt auch auf kleineren Fernsehern fokussierbar ist.
- Die DEVIDEOSRC-Embed-API bindet Videohoster eines Drittanbieters ein; Streaming-Verfügbarkeit, Lizenzlage, Vollbild, Fernbedienung und Kompatibilität sind **nicht garantiert**. Kein DRM-Bypass, kein Stream-Scraping, keine Download-Funktion.
- `https://devideosrc.co/docs` beschreibt die eingebundenen Player-Routen.
- **Build:** Unter GitHub **Actions → Build Android TV APK** den erfolgreichen Lauf öffnen und unter **Artifacts** `StreamHub-Premium-V1.8-debug-apk` herunterladen. Enthalten ist `app-debug.apk`. Für private Gerätetests; die Android-TV-Box wurde noch nicht real getestet.

---

# StreamHub Premium V1.7 — Android TV

Eigenständige Android-TV-App für Webseiten, Favoriten und öffentliche Cover-Vorschauen. Kodi bleibt separat. Stand: Android-Build wird über GitHub Actions ausgeführt; Laufzeit- und Webseiten-Kompatibilität muss auf der Xiaomi-TV-Box geprüft werden.

## Neu: Cover aus ALLEN hinterlegten Webseiten

- Unter **Cover entdecken** versucht die App, für jede gespeicherte Webseite mit gültiger HTTPS-Adresse bis zu acht öffentlich verlinkte Vorschaubilder zu finden. Jede Kachel führt zur passenden Detailseite **auf der Originalwebseite**.
- Zusätzlich ersetzen gefundene Vorschaubilder die grafischen Platzhalter bei Webseiten-Kacheln in Startseite, Kategorien und Favoriten.
- Pro Quelle wird **ohne Anmeldung, Captcha-Umgehung oder Zugriff auf Videostreams** ausschließlich öffentlich verfügbares Homepage-HTML verarbeitet. Relevante Quellen sind verlinkte `<img>`-Bilder sowie `og:image` und `twitter:image`.
- Bei JavaScript-generierten Katalogen, fehlenden URLs, Zugriffssperren, Anti-Bot-Regeln oder unpassenden Bildern funktioniert die Erkennung gegebenenfalls nicht. In diesem Fall bleibt das StreamHub-Premium-Artwork erhalten.
- Die Auswahl ist **heuristisch**. Die App kann Logos, Werbung oder falsche Vorschaubilder nicht in jedem Fall sicher von Film-/Anime-Covern unterscheiden. Eine automatische vollständige Film-/Serien-Datenbank ist das **nicht**.
- Automatisch höchstens drei Seitenabfragen gleichzeitig, begrenzte Größe und Laufzeit; lokale Ergebnis- und Bild-Caches. Das manuelle Aktualisieren erfolgt über **Cover aller Quellen aktualisieren**. Die Funktion kontaktiert beim Laden der Kacheln gespeicherte Quellen.
- **Bitte die Rechte und Nutzungsbedingungen der jeweiligen Webseiten beachten.** Nur weil ein Cover technisch zugänglich ist, ist seine freie Weiterverwendung nicht automatisch gestattet.
- Aus Datenschutz-/Sicherheitsgründen erfolgt kein Login-Scraping, keine Umgehung von Sperren und keine Video-/Download-Link-Extraktion.

Aktueller Starterkatalog: 57 Einträge, davon 36 mit URL und 21 Platzhalter. Eigene Webseiten werden ebenfalls berücksichtigt, sofern HTTPS-Adresse vorhanden.

## Build

Repository: `Heavensolder94/StreamHUB`. In GitHub unter **Actions → Build Android TV APK** den grünen Lauf öffnen, unter **Artifacts** `StreamHub-Premium-V1.7-debug-apk` herunterladen und `app-debug.apk` auf dem Android-TV installieren. Diese Debug-Datei ist nur für private Funktionstests.

---

## Historie – StreamHub V1.5

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
