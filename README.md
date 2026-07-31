# Arona Layers Generator

A Fabric mod that automatically generates terrain layer blocks during worldgen, turning vanilla grass, dirt and sand surfaces into multi-block layer stacks.

Supports **Minecraft 1.20.1, 1.21.1, 1.21.11 and 26.2** from a single shared codebase.

## Overview

The mod places sub-block-height layers on terrain so slopes read as smooth gradients rather than staircases. It needs two things: a **terrain source** that can tell it the fractional height of each column, and a **layer block provider**.

**Terrain sources** (pick one):

| Source | Notes |
|--------|-------|
| **ReTerraForged** | Recommended on 1.20.1 / 1.21.1. Uses RTF's cell height data for accurate gradient-based depth |
| **Tellus** | Real-world elevation. Uses Tellus's Digital Elevation Model — the sub-block fraction of each column's continuous elevation drives the layer count |
| **Vanilla** | No extra mod. Less accurate; see the note below |

**Layer block providers** (pick one):

| Provider | Notes |
|----------|-------|
| **Conquest Reforged** | Layer blocks, plants, rocks, foliage and NBT trees |
| **VanillaLayerPlus** | Layer blocks only |

Vanilla terrain generation with layers is CURSED, please don't use it, the results are bad, use RTF or Tellus!

## Features

- **RTF-based layer injection** — uses ReTerraForged's cell height data for realistic, gradient-based layer depth
- **Tellus-based layer injection** — layers driven by real-world elevation data, with land-cover-aware water and snow handling
- **Vanilla injection** — heightmap + noise-router fallback for worlds with no terrain mod (experimental)
- **Dual backend** — Conquest Reforged or VanillaLayerPlus
- **Plant handling** — converts vanilla plants to CR equivalents, or shifts them up for VLP
- **Underwater layers** — waterlogged layer blocks on ocean and river floors
- **Rocks & foliage** — biome-weighted CR rock and foliage placement above layers
- **Structure awareness** — skip or clean up layers inside, around and beneath structures
- **NBT tree placement** — replaces vanilla worldgen trees with hand-crafted CR NBT structures
- **Snowy biome handling** — skip, improve, or convert snow layers to mapped equivalents
- **Native layer blocks** — `deepslate_layer`, `moss_layer` and `powder_snow_layer` for surfaces the backend has no layer block for

## Requirements

| Minecraft | Fabric Loader | Fabric API | Java |
|-----------|---------------|------------|------|
| 1.20.1 | 0.15.11+ | 0.92.2+ | 17 |
| 1.21.1 | 0.16.0+ | 0.104.0+ | 21 |
| 1.21.11 | 0.19.3+ | 0.141.5+ | 21 |
| 26.2 | 0.19.3+ | 0.156.0+ | 25 |

Plus a layer block provider:

- [Conquest Reforged](https://www.curseforge.com/minecraft/mc-mods/conquest-reforged) *(recommended — required for plants, rocks, foliage and NBT trees)*
- VanillaLayerPlus *(layer blocks only)*

And, STRONGLY RECOMMENDED, a terrain source:

- [ReTerraForged](https://github.com/racoonman2/ReTerraForged) — you can use this [working fork](https://github.com/UF4OVER/ReTerraForged/releases/tag/0.0.6-fix2)
- or [Tellus](https://modrinth.com/mod/tellus) for real-world terrain

> Availability differs per Minecraft version. Conquest Reforged and ReTerraForged do not exist on every supported version; check before planning a setup. Without a layer block provider installed the mod loads but layer injection stays inactive, and says so in the log.

## Installation

1. Install Fabric Loader and Fabric API for your Minecraft version (see the table above)
2. Install Conquest Reforged **or** VanillaLayerPlus
3. Install ReTerraForged or Tellus *(recommended)*
4. Place the mod JAR for your Minecraft version in your mods folder
5. Launch once to generate config files in `config/aronalayersgen/`

### NBT Trees (Conquest Reforged only)

The NBT tree feature (`cr_nbt_trees`) requires hand-crafted tree structures in `.nbt` format that are not bundled with the mod.

See **[nbt_trees_guide.md](scripts/nbt_trees_guide.md)** for step-by-step instructions on generating this folder from an existing schematic pack by quitefrank_ly.

Once generated, place the `nbt_trees/` folder at:
```
config/aronalayersgen/nbt_trees/
```

## Configuration

All settings live in `config/aronalayersgen/layer_config.json`. The defaults below are what ships with the mod.

> **Default setup:** preconfigured for **RTF or Tellus + Conquest Reforged** with most features enabled. If you are using neither RTF nor Tellus, set `rtf_layer_injection: false` and `tellus_layer_injection: false`, and rely on `layer_injection` instead.

---

### Layer Injection

| Key | Default | Description |
|-----|---------|-------------|
| `rtf_layer_injection` | `true` | RTF-based layer generation — requires ReTerraForged |
| `tellus_layer_injection` | `true` | Tellus-based layer generation from real-world elevation data — requires Tellus |
| `layer_injection` | `true` | Vanilla heightmap-based layer generation |
| `vanilla_noise_router_layer_injection` | `false` | Improve vanilla mode using NoiseRouter density functions instead of the slope heuristic. No effect when RTF is active |
| `injection_mode` | `POST_FEATURES` | `CARVERS` (before features) or `POST_FEATURES` (after structures, cleaner result) |

Each injection path only runs when its terrain mod is present, so leaving all three enabled is safe.

> **Vanilla mode note:** `layer_injection` is functional but less accurate than RTF or Tellus injection — terrain heightmap heuristics produce inconsistent layer depth on complex terrain. Enabling `vanilla_noise_router_layer_injection` closes most of this gap.

---

### Tellus

| Key | Default | Description |
|-----|---------|-------------|
| `tellus_layer_injection` | `true` | Enable Tellus injection. The sub-block fractional part of each column's continuous elevation drives the layer count, and the ESA WorldCover land-cover class is sampled for water and snow handling |
| `tellus_reduce_layer_count` | `true` | Reduce the computed layer count by one. A legacy aesthetic tweak inherited from RTF — turning it **off** tracks the true sub-block elevation more closely and avoids the ~19% of columns that otherwise drop to zero layers on near-integer elevations. Snow columns always keep the raw value |

---

### Debug Commands

| Command | Description |
|---------|-------------|
| `/algtellus` | Inspect the column you are standing in: sampled elevation, predicted surface Y against what was actually built, the resolved surface block and the layer block it maps to, and whether a layer is present |
| `/algtellus grid` | The same over an area, for spotting patterns rather than single columns |
| `/algdebug` | General chunk inspection — heightmaps, surface blocks and mapping resolution |

These are the fastest way to work out why a particular column did or did not get a layer. Pair them with `debug_log_tellus`, which logs the specific reason a column was skipped.

---

### Snowy Biomes

| Key | Default | Description |
|-----|---------|-------------|
| `skip_snowy_biomes` | `false` | Skip all layer placement in cold/snowy biomes |
| `improve_snowy_biomes` | `true` | Use vanilla snow layers in snowy biomes instead of skipping. Requires `skip_snowy_biomes: false` |
| `break_snow_layers_to_mapped_layers` | `true` | When a snow layer is broken, replace it with the mapped layer block for the block below, reduced by 1 |

---

### Plants & Decoration

| Key | Default | Description |
|-----|---------|-------------|
| `plant_injection` | `true` | Convert vanilla plants above layers to CR equivalents, or shift them up onto the layer for VLP |
| `replace_sea_grass` | `true` | Also convert seagrass and tall_seagrass |
| `tree_injection` | `true` | Allow trees to generate through layer blocks |
| `underwater_layers` | `true` | Place waterlogged layers on underwater surfaces |
| `replace_dirt_path` | `true` | Replace dirt_path with the mapped solid block when placing a layer on it |
| `place_wet_sand` | `true` | Use CR wet_sand_layer instead of sand_layer when the position is waterlogged or below sea level |

---

### Rocks

| Key | Default | Description |
|-----|---------|-------------|
| `place_rocks` | `true` | Place CR rock blocks above layers |
| `conquest_enhanced_rocks` | `true` | Per-biome weighted rocks using `cr_enhanced_rock_mappings.json`. Falls back to `cr_rock_mappings.json` for unregistered biomes |
| `chance_to_place_rocks` | `0.05` | Probability per eligible position (0.0–1.0) |
| `rock_density_mode` | `DECREASED` | `RANDOM` (uniform) or `DECREASED` (biased toward smaller rocks) |
| `rock_density_factor` | `4.0` | Bias strength for DECREASED mode — higher = more small rocks (~75/19/5/1% at 4.0) |

---

### Foliage

| Key | Default | Description |
|-----|---------|-------------|
| `place_extra_foliage` | `true` | Place CR foliage above layers |
| `conquest_enhanced_extra_foliage` | `true` | Per-biome weighted foliage using `cr_enhanced_extra_foliage_mappings.json` |
| `chance_to_place_extra_foliage` | `0.5` | Probability per eligible position (0.0–1.0) |

---

### NBT Trees

| Key | Default | Description |
|-----|---------|-------------|
| `cr_nbt_trees` | `true` | Replace vanilla worldgen trees with CR NBT structures. Requires the `nbt_trees/` folder — see [nbt_trees_guide.md](scripts/nbt_trees_guide.md). Automatically disabled when Conquest Reforged is not installed |
| `cr_nbt_trees_vanilla_fallback` | `true` | If no NBT variant is found for a sapling, let vanilla tree growth proceed. `false` suppresses vanilla growth entirely |

Tree species, biome assignments and sapling mappings are configured in `cr_nbt_trees.json`. NBT files live in `config/aronalayersgen/nbt_trees/<species>/`.

---

### Structure Awareness

| Key | Default | Description |
|-----|---------|-------------|
| `structure_injection` | `true` | Skip layers inside structure bounding boxes |
| `structure_injection_extra_bounds` | `false` | Expand structure bounding boxes outward by a buffer zone |
| `structure_injection_extra_bounds_distance` | `4` | Buffer radius in blocks |
| `cross_chunk_structure_detection` | `false` | Also check neighboring chunks for structures that start outside the current chunk. Disable during pre-generation with Chunky or similar |
| `structure_no_layers` | `false` | Remove layers where the structure changed the terrain heightmap. More precise than bounding-box detection |
| `structure_cleanup` | `false` | Remove layers under opaque structure blocks after generation |
| `enclosed_space_check` | `true` | Skip layers under solid ceilings (cave interiors, buildings). Requires `structure_injection`. Tree canopies are not treated as ceilings |
| `enclosed_space_height` | `7` | Height in blocks to scan upward for a ceiling |
| `second_pass_cleanup` | `false` | After structure detection removes layers, taper neighboring layer values to avoid abrupt edges |

#### Structure footprint (advanced)

Piece-level bounding boxes miss the ground *between* buildings — cleared plazas, paths and courtyards. Footprint detection uses the overall XZ hull of each structure instead. Trees are unaffected, as they have no StructureStart.

| Key | Default | Description |
|-----|---------|-------------|
| `structure_footprint_check` | `false` | Suppress layers within the whole XZ footprint of each structure, not just its individual pieces. Requires `structure_injection` |
| `structure_footprint_below_margin` | `8` | Blocks below the structure's minimum Y to still suppress. Handles terrain carved into (paths dug into a hillside, excavated basements) |
| `structure_footprint_above_margin` | `4` | Blocks above the structure's maximum Y to still suppress. Keep small, or surface layers legitimately above an underground structure get suppressed too |

#### Conservative surface (advanced, RTF only)

Compares each column's actual surface against the terrain source's expected base height. A deviation means something modified the terrain there, without needing bounding boxes at all.

| Key | Default | Description |
|-----|---------|-------------|
| `conservative_surface_heightmap` | `true` | Skip columns whose surface deviates from the expected base beyond the tolerances below |
| `conservative_surface_tolerance_up` | `0` | Maximum upward deviation allowed, in blocks. `1` tolerates natural snow while still catching terrain raised by 2+ |
| `conservative_surface_tolerance_down` | `0` | Maximum downward deviation allowed. `0` is strict; increase if natural slopes cause false positives |
| `conservative_surface_fallback` | `true` | Place a fixed-value layer on a deviating column instead of skipping it outright |
| `conservative_surface_fallback_value_up` | `0` | Layer value (1–8) where terrain was raised; `0` skips |
| `conservative_surface_fallback_value_down` | `4` | Layer value (1–8) where terrain was lowered; `0` skips |

#### Structure skip-elevated (advanced)

| Key | Default | Description |
|-----|---------|-------------|
| `structure_skip_elevated` | `false` | Skip layers where a structure raised the terrain above the natural surface |
| `structure_skip_extra_cleanup` | `false` | Also remove layers within a radius of each detected elevated position |
| `structure_skip_extra_cleanup_distance` | `2` | Chebyshev radius in blocks for cleanup scan |
| `structure_replace_elevated` | `false` | If a structure placed a mapped block exactly 1 block above natural terrain, replace it with a layer instead of skipping |
| `structure_replace_elevated_high` | `false` | Extend replacement to elevations greater than 1 block |
| `structure_replace_elevated_high_max` | `3` | Maximum elevation delta to apply the high-elevation replacement |

---

### Debug Logging

`debug_logging` is the master switch. The per-category flags default to `true` but do nothing until the master switch is on.

| Key | Default | Description |
|-----|---------|-------------|
| `debug_logging` | `false` | Master switch — every flag below requires this to be `true` |
| `debug_log_chunk_init` | `true` | Chunk initialization |
| `debug_log_rtf` | `true` | RTF injection |
| `debug_log_tellus` | `true` | Tellus injection, including a per-column reason when a layer is not placed |
| `debug_log_structure` | `true` | Structure detection |
| `debug_log_skip_elevated` | `true` | Structure skip-elevated detection |
| `debug_log_structure_no_layers` | `true` | Heightmap-based structure detection |
| `debug_log_second_pass` | `true` | Second-pass cleanup |
| `debug_log_vanilla` | `true` | Vanilla injection |
| `debug_log_nbt_trees` | `true` | NBT tree placement |
| `debug_log_foliage` | `true` | Foliage placement |
| `debug_log_plants` | `true` | Plant conversion |
| `debug_log_rocks` | `true` | Rock placement |
| `debug_log_snow` | `true` | Snow layer handling |
| `debug_log_tree_soil` | `true` | Tree soil clearing |
| `debug_log_correction` | `true` | Layer value correction |

---

### Block Mapping Files

All files are auto-created in `config/aronalayersgen/` on first launch:

| File | Backend | Description |
|------|---------|-------------|
| `cr_block_mappings.json` | CR | Vanilla block → CR layer block |
| `vp_block_mappings.json` | VLP | Vanilla block → VLP layer block |
| `plant_mappings.json` | Both | Vanilla plant → CR plant, or `null` to shift the plant up for VLP |
| `cr_rock_mappings.json` | CR | Surface block → CR rock |
| `cr_extra_foliage_mappings.json` | CR | Surface block → CR foliage |
| `cr_enhanced_rock_mappings.json` | CR | Per-biome weighted rock mappings |
| `cr_enhanced_extra_foliage_mappings.json` | CR | Per-biome weighted foliage mappings |
| `cr_nbt_trees.json` | CR | NBT tree species, biomes and sapling mappings |
| `second_pass_cleanup_blocks.json` | Both | Blocks that trigger layer cleanup |
| `structure_elevation_blocks.json` | Both | Blocks that indicate structure-elevated terrain |

The active block mapping file (CR vs VLP) is chosen automatically based on which layer mod is installed.

If a mapping names a block that is not registered — usually because the backend does not provide it — the mod logs `Layer block not found in registry` at startup and skips that mapping. A clean startup log means the mapping files and your installed mods agree.

Modded plants can be added to `plant_mappings.json` by block ID: give a Conquest equivalent as the value for CR, or `null` for VLP.

## Building from Source

```
./gradlew build
```

Each Minecraft version is a Gradle subproject sharing one source tree. JARs land in each subproject's `build/libs/`:

```
v1_20_1/build/libs/    v1_21_1/build/libs/    v1_21_11/build/libs/    v26_2/build/libs/
```

Build a single version with `./gradlew :v1_21_1:build`.

`scripts/verify_mixins.py` checks that every mixin in a built JAR resolves against its Minecraft version — worth running before releasing, since an unresolvable mixin target still builds successfully and only fails when the game loads.

## A note on how this fork is developed

I use Claude AI as a development aid, mainly for the repetitive parts of multi-version maintenance, tracking down what a renamed Minecraft API became, debugging, reading mods sources to find how to implement my layers mechanic, etc.

That is where the help stops. I decide what goes in, I read the changes, and I test in-game before releasing. Fixes get re-tested after the fact, not assumed.

I am saying this because "AI-assisted" often means code nobody checked. That is not what this is. If you find a bug anyway, please open an issue — I would rather hear about it.

## License

MIT License

## Author

Arona74
