# FairPlay – Fair Minecraft

[![Release](https://img.shields.io/github/v/release/flyingfinger1/fairplay?label=release&color=brightgreen)](https://github.com/flyingfinger1/fairplay/releases/latest)
[![Paper](https://img.shields.io/badge/Paper-1.17–1.18.2-f96854)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-17-blue)](https://adoptium.net)
[![License](https://img.shields.io/github/license/flyingfinger1/fairplay)](LICENSE)
[![JavaDoc](https://img.shields.io/badge/JavaDoc-online-blue)](https://flyingfinger1.github.io/fairplay/)

A Paper plugin that enforces one simple rule:

> **You may only break blocks that you placed yourself.**

---

## Supported Versions

Download the JAR matching your server version from [Releases](https://github.com/flyingfinger1/fairplay/releases/latest):

| JAR | Minecraft | Java |
|-----|-----------|------|
| `FairPlay-x.x.x-mc1.21.4+.jar` | 1.21.4 and newer | 21 |
| `FairPlay-x.x.x-mc1.20.5-1.21.3.jar` | 1.20.5 – 1.21.3 | 21 |
| `FairPlay-x.x.x-mc1.19-1.20.4.jar` | 1.19 – 1.20.4 | 17 |
| `FairPlay-x.x.x-mc1.17-1.18.2.jar` | 1.17 – 1.18.2 | 17 |

---

## Concept

Every block a player places is assigned to them in a database. Foreign blocks cannot be broken — not directly, and not indirectly. This creates a cooperative survival experience where resources and structures truly "belong" to a player.

The plugin supports two modes: **solo** (strict ownership, default) and **team** (shared ownership, restrictions disabled).

## Features

### Block Ownership
- Every placed block is registered to the player (SQLite database)
- Foreign blocks cannot be broken
- **Creative mode** bypasses all checks automatically
- Two-block structures (beds, doors, tall plants) are correctly treated as a unit
- **Gravity blocks** (sand, gravel, concrete powder, anvils …) retain their ownership when they fall and land at a new position

### Fluids & Resources
- Water and lava buckets can only be filled from the player's own sources
- Waterlogged blocks (e.g. fence in water) check for adjacent owned water sources
- Glass bottles can only be filled from the player's own water sources
- Cauldrons can only be emptied by the owner
- Crops can only be fertilised by the owner

### Plants & Growth
- Naturally grown blocks (sugar cane, cactus, bamboo, kelp …) inherit ownership from the base block
- Trees grown from the player's own saplings belong to the planter
- Sweet berries can only be harvested by the bush owner
- Farmland cannot be trampled by other players
- Dripstone tips that grow from an owned stalactite or stalagmite are assigned to the owner

### Mob Ownership
- Animals bred or tamed by a player are registered in the database
- Only the owner may shear sheep, milk cows, and collect mushroom stew
- Wild animals (not bred/tamed) are blocked for everyone — they cannot be interacted with
- **Turtle egg cycle:** feeding a wild turtle marks its eggs; the hatched babies are owned by the feeder. Feeding an owned turtle produces directly owned (breakable) eggs.

### 28 Custom Advancements
The plugin ships its own advancement tree explaining and rewarding FairPlay mechanics — from "Foundation" (first own block) to "First Night" (surviving until dawn).

### Team / Solo Mode
Configure in `config.yml` whether players compete individually or share resources as a team:

```yaml
# solo  → each player only owns what they placed themselves (default)
# team  → all players share ownership, no restrictions apply
game-mode: solo
```

### Multilingual Support
All player messages and advancement texts are automatically displayed in the player's client language. Currently supported languages:
- 🇺🇸 English (`en_us`)
- 🇩🇪 German (`de_de`)
- 🇫🇷 French (`fr_fr`)
- 🇪🇸 Spanish (`es_es`)
- 🇧🇷 Portuguese Brazil (`pt_br`)
- 🇮🇹 Italian (`it_it`)
- 🇳🇱 Dutch (`nl_nl`)
- 🇵🇱 Polish (`pl_pl`)
- 🇷🇺 Russian (`ru_ru`)
- 🇨🇳 Chinese Simplified (`zh_cn`)
- 🇯🇵 Japanese (`ja_jp`)
- 🇰🇷 Korean (`ko_kr`)

Adding a new language only requires adding a file to `src/main/resources/lang/` and `src/main/resources/resourcepack/assets/fairplay/lang/`.

---

## Installation

### Version Notes (1.17 – 1.18.2)

The following features from the latest version are not available on this branch:

- **Frog/tadpole egg cycle** is not tracked (Frogs were added in 1.19 — only the turtle egg cycle is supported)
- **Armadillo brushing** is not tracked (Armadillos were added in 1.20.5)
- **`resource-pack-required: true`** has no effect — Paper 1.17–1.18 does not have a kick-on-decline API. The resource pack is always sent, but players can decline it without consequence
- **Sniffer Egg** is not in the Wandering Trader pool (added in 1.20)
- **Swift Sneak III** is not in the Wandering Trader pool (added in 1.19)
- **Music Disc 5, Otherside, and Relic** are not in the Wandering Trader pool (added in 1.19/1.20)
- **Mangrove Propagule and Cherry Sapling** do not trigger the Green Thumb advancement (added in 1.19/1.20)
- **Sculk blocks** are not tracked for ownership transfer on spread (Sculk was added in 1.19)

### Requirements
- Paper 1.17 – 1.18.2
- Java 17 (compiles to Java 16 bytecode for compatibility)
- Internet access on first start (Paper downloads `sqlite-jdbc` from Maven Central automatically)

### Build
```bash
gradle build
```
The finished JAR is located in `build/libs/FairPlay-1.0.3.jar`.

### Configuration
After the first start, `plugins/FairPlay/config.yml` is created:

```yaml
# solo  → each player only owns what they placed themselves (default)
# team  → all players share ownership, no restrictions apply
game-mode: solo

# Resource pack (translations for advancements).
# On every start the plugin saves fairplay-resourcepack.zip to plugins/FairPlay/.
#
# Option A – External URL (recommended, e.g. GitHub Releases):
# resource-pack-url: https://github.com/YOUR_USER/FairPlay/releases/latest/download/fairplay-resourcepack.zip
#
# Option B – Embedded HTTP server (local/LAN only, no URL set):
resource-pack-host: localhost   # For a dedicated server: external IP or domain
resource-pack-port: 8765

# true  = clients must accept the pack (otherwise kicked)
# false = clients may decline (they will only see raw translation keys)
resource-pack-required: false
```

> **Resource pack hosting:** On every start the plugin writes `plugins/FairPlay/fairplay-resourcepack.zip`. Upload that file to GitHub Releases and set `resource-pack-url` — the embedded HTTP server is then not started. Without the pack, raw translation keys are displayed in advancements.

---

## Project Structure

```
src/main/
├── java/de/fairplay/
│   ├── FairPlayPlugin.java          # Plugin main class
│   ├── Lang.java                    # Multilingual support (player.locale())
│   ├── ResourcePackServer.java      # Embedded HTTP server
│   ├── advancements/
│   │   └── AdvancementManager.java  # Data pack installation & advancement granting
│   ├── listeners/
│   │   ├── BlockOwnershipListener.java  # Core mechanic: block ownership + falling blocks
│   │   ├── GrowthListener.java          # Growth & spread (incl. dripstone)
│   │   ├── CauldronListener.java        # Cauldron ownership
│   │   ├── CombatListener.java          # Combat rules
│   │   ├── MobInteractionListener.java  # Mob ownership (breed/tame, shear, milk, brush)
│   │   ├── LootListener.java            # Loot & items
│   │   ├── VehicleListener.java         # Boats & minecarts
│   │   ├── AdvancementListener.java     # Custom advancement triggers
│   │   └── ResourcePackListener.java    # Resource pack on login
│   └── storage/
│       └── OwnershipStorage.java        # SQLite database access
└── resources/
    ├── config.yml
    ├── plugin.yml
    ├── lang/                        # Server-side translations (action bar messages)
    │   ├── en_us.properties
    │   └── de_de.properties
    ├── datapack/                    # Custom advancements (data pack)
    │   └── data/fairplay/advancement/
    └── resourcepack/                # Client-side translations
        └── assets/fairplay/lang/
            ├── en_us.json
            └── de_de.json
```

---

## Technical Details

| Component | Technology |
|---|---|
| Server API | Paper 1.17–1.18.2 (compiled against `paper-api:1.17.1-R0.1-SNAPSHOT`) |
| Database | SQLite via `sqlite-jdbc:3.45.1.0` (loaded by Paper on first start) |
| Build | Gradle |
| Java | 16 bytecode (runs on Java 17) |
| Data pack format | 81 |
| Resource pack format | 46 (compatible with 32–9999) |
