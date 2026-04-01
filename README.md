# FairPlay – Faires Minecraft

Ein Paper-Plugin für Minecraft 1.21.8, das eine einzige Regel durchsetzt:

> **Du darfst nur abbauen, was du selbst gebaut hast.**

---

## Spielprinzip

Jeder Block den ein Spieler platziert, wird ihm zugewiesen. Fremde Blöcke können nicht abgebaut werden – auch nicht indirekt. Das schafft ein kooperatives Survival-Erlebnis, bei dem Ressourcen und Strukturen wirklich einem Spieler „gehören".

## Features

### Block-Ownership
- Jeder platzierte Block wird dem Spieler zugewiesen (SQLite-Datenbank)
- Fremde Blöcke können nicht abgebaut werden
- **Creative-Modus** bypassed alle Checks automatisch
- Zweiblock-Strukturen (Betten, Türen, große Pflanzen) werden korrekt als Einheit behandelt

### Flüssigkeiten & Ressourcen
- Wasser- und Lava-Eimer können nur aus eigenen Quellen gefüllt werden
- Waterlogged-Blöcke (z. B. Zaun im Wasser) prüfen angrenzende eigene Wasserquellen
- Glasflaschen können nur an eigenen Wasserquellen gefüllt werden
- Kessel können nur vom Eigentümer geleert werden
- Felder dürfen nur vom Eigentümer gedüngt werden

### Pflanzen & Wachstum
- Natürlich gewachsene Blöcke (Zuckerrohr, Kaktus, Bambus, Kelp …) erben die Ownership des Stammes
- Bäume aus eigenen Setzlingen gehören dem Pflanzer
- Süßbeeren können nur vom Eigentümer des Busches geerntet werden
- Felder können nicht von Fremden zertrampelt werden

### 27 Custom Advancements
Das Plugin bringt einen eigenen Advancement-Baum mit, der die FairPlay-Mechaniken erklärt und belohnt – von „Grundstein" (erster eigener Block) bis „Züüündung" (Creeper als Werkzeug).

### Mehrsprachigkeit
Alle Spieler-Meldungen und Advancement-Texte werden automatisch in der Client-Sprache angezeigt. Aktuell unterstützte Sprachen:
- 🇩🇪 Deutsch (`de_de`)
- 🇺🇸 Englisch (`en_us`)

Neue Sprachen können durch Hinzufügen einer Datei in `src/main/resources/lang/` und `src/main/resources/resourcepack/assets/fairplay/lang/` ergänzt werden.

---

## Installation

### Voraussetzungen
- Paper 1.21.8
- Java 21

### Build
```bash
./gradlew build
```
Die fertige JAR liegt in `build/libs/FairPlay-1.0.0.jar`.

### Deploy
```bash
./gradlew deploy
```
Kopiert die JAR direkt in `server/plugins/` (lokale Entwicklungsumgebung).

### Konfiguration
Nach dem ersten Start wird `plugins/FairPlay/config.yml` erstellt:

```yaml
# Adresse die Clients für das Resource Pack erreichen können
resource-pack-host: localhost   # Bei Dedicated Server: externe IP oder Domain
resource-pack-port: 8765

# true = Clients müssen das Pack akzeptieren (sonst Kick)
resource-pack-required: false
```

> **Hinweis:** Das Resource Pack wird automatisch beim Einloggen an Spieler gesendet und enthält die Übersetzungen für die Advancement-Texte. Ohne das Pack werden rohe Übersetzungsschlüssel angezeigt.

---

## Projektstruktur

```
src/main/
├── java/de/fairplay/
│   ├── FairPlayPlugin.java          # Plugin-Hauptklasse
│   ├── Lang.java                    # Mehrsprachigkeit (player.locale())
│   ├── ResourcePackServer.java      # Eingebetteter HTTP-Server
│   ├── advancements/
│   │   └── AdvancementManager.java  # Data Pack Installation & Advancement-Vergabe
│   ├── listeners/
│   │   ├── BlockOwnershipListener.java  # Kern-Mechanic: Block-Ownership
│   │   ├── GrowthListener.java          # Wachstum & Ausbreitung
│   │   ├── CauldronListener.java        # Kessel-Ownership
│   │   ├── CombatListener.java          # Kampfregeln
│   │   ├── LootListener.java            # Loot & Items
│   │   ├── VehicleListener.java         # Boote & Loren
│   │   ├── AdvancementListener.java     # Custom Advancement Trigger
│   │   └── ResourcePackListener.java    # Resource Pack bei Login
│   └── storage/
│       └── OwnershipStorage.java        # SQLite-Datenbankzugriff
└── resources/
    ├── config.yml
    ├── plugin.yml
    ├── lang/                        # Server-seitige Übersetzungen (ActionBar)
    │   ├── de_de.properties
    │   └── en_us.properties
    ├── datapack/                    # Custom Advancements (Data Pack)
    │   └── data/fairplay/advancement/
    └── resourcepack/                # Client-seitige Übersetzungen
        └── assets/fairplay/lang/
            ├── de_de.json
            └── en_us.json
```

---

## Technische Details

| Komponente | Technologie |
|---|---|
| Server-API | Paper 1.21.8 (`paper-api:1.21.8-R0.1-SNAPSHOT`) |
| Datenbank | SQLite via `sqlite-jdbc:3.45.1.0` (gebundelt) |
| Build | Gradle (Fat JAR) |
| Java | 21 |
| Data Pack Format | 81 |
| Resource Pack Format | 46 (kompatibel mit 32–9999) |
