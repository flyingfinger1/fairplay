# FairPlay — CLAUDE.md

Interne Projektnotizen für Claude. Deutsch als Arbeitssprache, Code/Commits auf Englisch.

---

## Was ist FairPlay?

Ein Paper-Plugin (1.21.8) für einen kompetitiven Survival-Modus. Das Grundprinzip:
**Jeder Spieler darf nur Blöcke abbauen, die er selbst gesetzt hat.**
Natürlich generierte (unowned) und fremde Blöcke sind für alle gesperrt.

Modi:
- `solo` (Default) — strikte Ownership, jeder für sich
- `team` — Ownership-Checks deaktiviert, alle teilen alles

---

## Technischer Stack

| Was | Version |
|---|---|
| Paper API | 1.21.8-R0.1-SNAPSHOT |
| Java | 21 |
| Build | Gradle (kein Wrapper im Repo, globales Gradle 9.1.0) |
| Datenbank | SQLite via `org.xerial:sqlite-jdbc` (Paper lädt es via `plugin.yml libraries`) |

---

## Build & Deploy

```bash
gradle build          # Nur bauen → build/libs/FairPlay-x.x.x.jar
gradle deploy         # Bauen + nach server/plugins/ kopieren
```

`deploy` ist in `local.gradle` definiert (nicht im Git), `build.gradle` bindet es per `apply from` ein wenn vorhanden.

---

## Dateistruktur

```
src/main/java/de/fairplay/
├── FairPlayPlugin.java              # onEnable: alles initialisieren & Listener registrieren
├── Lang.java                        # Lokalisierung (Properties-Dateien)
├── ResourcePackServer.java          # Embedded HTTP-Server für den Ressourcenpack
├── advancements/
│   └── AdvancementManager.java      # Custom Advancements installieren & vergeben
├── storage/
│   └── OwnershipStorage.java        # SQLite-Wrapper (block_ownership + entity_ownership)
└── listeners/
    ├── BlockOwnershipListener.java  # Kern: Block setzen/abbauen + alle Tool-Interaktionen
    ├── GrowthListener.java          # Wachstum (Bäume, Crops, Pilze, Schildkrötenei, …)
    ├── PistonListener.java          # Kolben: Ownership-Check + DB-Migration
    ├── CauldronListener.java        # Kessel-Interaktionen
    ├── CombatListener.java          # Kampf-relevante Events
    ├── LootListener.java            # Beute-Advancements
    ├── MobInteractionListener.java  # Tiere: Züchten, Zähmen, Scheren, Melken
    ├── VehicleListener.java         # Fahrzeuge (Boote, Loren)
    ├── AdvancementListener.java     # Advancement-Trigger
    └── ResourcePackListener.java    # Ressourcenpack senden/erzwingen
```

---

## Kern-Konzept: Ownership-Modell

### Datenbank

Zwei SQLite-Tabellen in `plugins/FairPlay/ownership.db`:

```sql
block_ownership  (world TEXT, x INT, y INT, z INT, owner TEXT)  -- PK: world+x+y+z
entity_ownership (entity_uuid TEXT, owner TEXT)                 -- PK: entity_uuid
```

`owner` ist immer eine UUID als String. `null`-Rückgabe = kein Eintrag = unowned.

### Grundregel

```
owner == null                  → niemand darf den Block berühren
owner != null && owner != ich  → gesperrt (msg.break, trespassing-Advancement)
owner == ich                   → erlaubt
```

`teamMode == true` → alle Ownership-Checks werden übersprungen.

---

## Listener-Übersicht

### BlockOwnershipListener

Der zentrale Listener. Schützt Blöcke gegen alle direkten Spieler-Interaktionen.

**Ownership setzen:**
- `onBlockPlace` — BlockPlaceEvent: Owner registrieren; Bett/Tür = beide Hälften
- `onBucketEmpty` — Wasser/Lava-Eimer: Owner setzen + Infinite-Water-Source erkennen
- `onFallingBlockSpawn/Land/Remove` — Gravity-Blöcke (Sand, Kies, …) tracken & neu zuordnen

**Ownership prüfen / sperren:**
- `onBlockBreak` — Hauptschutz; Fallbacks für Bambus, Dripstone, Melone/Kürbis
- `onBucketFill` — Wasser/Lava-Eimer füllen nur aus eigenen Quellen
- `onBottleFill` — Glasflasche nur an eigenen Wasserquellen befüllen
- `onBerryPick` — Beerenbusch nur eigene Beeren ernten
- `onFarmlandTrample` — Farmland UND Schildkrötenei: nur Owner darf zertrampeln
- `onHiveHarvest` — Bienenstock/-nest (Flasche + Schere) nur wenn voll & owned
- `onToolTransform` — Schaufel (→ Dirt Path), Hacke (→ Farmland), Axt (Strip/Scrape Kupfer) nur auf eigenen Blöcken

**Konstanten für Tool-Schutz:**
```java
SHOVEL_PATH_MATERIALS  // GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM
HOE_TILL_MATERIALS     // DIRT, GRASS_BLOCK, COARSE_DIRT, ROOTED_DIRT
AXE_STRIP_MATERIALS    // alle nicht-gestripten Logs/Holz/Stämme/Hyphen/BambooBlock
                       // + alle WAXED_*/OXIDIZED_*/WEATHERED_*/EXPOSED_* Kupfer-Varianten
                       // (dynamisch per Material.values() beim Classload gebaut)
```

### GrowthListener

Sorgt dafür dass Ownership bei Wachstum mitgenommen wird.

- `onStructureGrow` — Baum/Pilz: alle neuen Blöcke kriegen Owner des Sämlings
- `onBlockFertilize` — Knochenmehl: nur auf eigenen Blöcken; neue Blöcke → Owner übertragen; Dispenser (player==null) → kein Check, keine Übertragung
- `onBlockGrow` — Zuckerrohr, Kaktus, Ranken, Kelp, Bambus: Owner von angrenzendem Block erben
- `onBlockSpread` — Ausbreitung (Sculk, Ranken, Moos, Beerenbusch, Kelp) → Owner übertragen; Gras/Myzel intentional ausgenommen
- `onBlockForm` — Stein-/Kobbelstein-Generator: Owner von angrenzendem Wasser/Lava; **Schildkrötenei (EntityBlockFormEvent)**: Owner der Schildkröte übernehmen; alle anderen EntityBlockFormEvents (Frost Walker, Silberfisch) → kein Neighbor-Scan, unowned lassen
- `onDripstonePhysics` / `assignDripstoneOwnership` — Dripstone-Wachstum (BlockPhysicsEvent, da BlockGrowEvent in Paper 1.21.8 nicht feuert)

### PistonListener

- **NORMAL-Priority**: `canMove()` prüft ob jeder Block in der Kette **explizit** dem Kolben-Owner gehört. Unowned = abgelehnt. Fremder Owner = abgelehnt. → Event gecancelt wenn nein (kein Feedback, verhält sich wie immovable Block).
- **MONITOR-Priority**: `migrateOwners()` verschiebt DB-Einträge zur neuen Position (von hinten nach vorne iterieren, damit kein Eintrag überschrieben wird bevor er gelesen wurde).
- Retract: Bewegungsrichtung = `event.getDirection().getOppositeFace()`

### MobInteractionListener

- `onEntityBreed` — Nachkomme → Owner = Spieler der gebreedert hat
- `onEntityTame` — Gezähmtes Tier → Owner = Spieler
- `onEntityDeath` — Entity-Ownership bereinigen
- `onShear` / `onInteractEntity` — Scheren, Melken, Bürsten: nur wenn Tier owned by Spieler

**Schildkrötenei-Flow (Zwei-Generationen-Zyklus):**

Schildkröten produzieren beim Breeding **kein Baby-Entity** — `EntityBreedEvent` feuert daher nicht für Schildkröten.

Vier neue DB-Tabellen/Konzepte neben `block_ownership` / `entity_ownership`:
- `entity_fedby` — wer hat diese Schildkröte zuletzt gefüttert?
- `block_fedby` — wer ist für diesen Ei-Block verantwortlich (übernommen von entity_fedby)?

Gilt für **Schildkröten** (Seegras → Ei → Baby-Schildkröte) und **Frösche** (Schleimball → Frogspawn → Kaulquappe → Frosch).

**Zyklus 1 — Wilde Tiere füttern:**
1. `onEggLayerFed` (`EntityEnterLoveModeEvent`): Spieler füttert Schildkröte/Frosch → `entity_fedby[entity] = player`
2. `onBlockForm` (`EntityBlockFormEvent`): Tier legt Ei/Frogspawn → `block_fedby[block] = entity_fedby[entity]`
   - Falls Tier auch `entity_ownership` hat → zusätzlich `block_ownership[block] = owner` (Zyklus 2)
3. `onEggHatch` (`CreatureSpawnEvent`): Schlüpfendes Baby/Kaulquappe → `entity_ownership[baby] = block_fedby[block]`
   - Nach letztem Ei/Frogspawn: `block_ownership` und `block_fedby` bereinigen
4. **Nur Frösche:** `onTadpoleGrow` (`EntityTransformEvent`): Kaulquappe → Frosch → `entity_ownership` auf neue Entity-UUID übertragen

**Zyklus 2 — Eigene Tiere füttern:**
- Baby/Frosch aus Zyklus 1 hat `entity_ownership` → wenn man es erneut füttert → Ei/Frogspawn kriegt auch `block_ownership`
- Diese Blöcke darf der Owner abbauen / zertrampeln (normaler BlockOwnershipListener-Check)

**Sonderregeln:**
- Dispenser-Fütterung (`humanEntity == null`) → kein `entity_fedby`-Eintrag → Blöcke bleiben unowned
- Marker bleibt auf Tier bis es neu gefüttert wird (überschrieben) oder stirbt (`onEntityDeath` bereinigt entity_ownership + entity_fedby)
- Multi-Ei-Block (Schildkröten, bis zu 4 Eier): Einträge bleiben bis letztes Ei weg ist; jedes Baby liest `block_fedby` beim Schlüpfen (vor dem 1-Tick-Delay)

---

## Design-Entscheidungen

| Entscheidung | Begründung |
|---|---|
| Unowned Blöcke = niemand darf abbauen | Natürliches Terrain soll unveränderlich sein; Spieler müssen Ressourcen selbst produzieren |
| Explosionen absichtlich NICHT geblockt | Designentscheidung des Owners: TNT/Creeper sollen wirken |
| Dispenser bei Knochenmehl kein Check | Dispenser haben keinen Player → `event.getPlayer() == null` → return early, keine Übertragung |
| Gras/Myzel-Spread überträgt keine Ownership | "Spieler besitzt bereits den Ziel-Dirt wenn er ihn selbst gesetzt hat" |
| Kolben: auch unowned Blöcke gesperrt | Natürliches Terrain darf nicht verschoben werden |
| Schildkrötenei: Zwei-Generationen-Zyklus | Cycle 1: wilde Schildkröten füttern → Eier unowned aber mit fedBy → Babies owned. Cycle 2: owned Schildkröten füttern → Eier owned → abbaubar |

---

## Bekannte offene Punkte

| Thema | Status | Notiz |
|---|---|---|
| Explosionen | Offen — absichtlich | Designentscheidung des Owners |
| Endermen klauen Blöcke | Offen — absichtlich | Wie Explosionen: wenn jemand Endermen nutzen will, soll er das |
| Dispenser saugt fremdes Wasser/Lava ab | Offen | PlayerBucketFillEvent feuert nicht für Dispenser |

---

## In dieser Session gefixte Lücken

1. **Schaufel/Hacke** (`onToolTransform`) — `PlayerInteractEvent` für Erde-Transformationen war ungeschützt
2. **Axt** (`onToolTransform`) — Logs strippen, Kupfer scrapen/entwachsen war ungeschützt
3. **Bienenstöcke** (`onHiveHarvest`) — Glasflasche + Schere auf fremdem Bienenstock war ungeschützt
4. **Schildkrötenei Trampling** (`onFarmlandTrample`) — nur FARMLAND war geprüft, TURTLE_EGG fehlte
5. **Schildkrötenei Zwei-Generationen-Flow** (`entity_fedby`, `block_fedby` + alle vier Handler) — kompletter Zwei-Zyklus implementiert: wilde Schildkröten füttern → Babies owned; owned Schildkröten erneut füttern → Eier owned+abbaubar
6. **Schildkrötenei Schlüpfen** (`GrowthListener.onTurtleHatch`) — `block_fedby` wird auf Baby-`entity_ownership` übertragen; beide Block-Einträge bereinigt wenn letztes Ei weg
7. **Kolben** (`PistonListener`, neu) — komplett fehlend; jetzt: Check + DB-Migration für Extend und Retract
