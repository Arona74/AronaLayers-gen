# Arona Layers Generator

A Fabric mod for Minecraft 1.20.1 that automatically generates terrain layer blocks during worldgen, turning vanilla grass, dirt and sand surfaces into multi-block layer stacks.

## Overview

The mod is built and tuned for **ReTerraForged + Conquest Reforged OR ReTerraForged + VanillaLayerPlus**. Those combinations are what the default config targets: RTF provides the terrain cell height data that drives accurate, gradient-based layer depth, and Conquest Reforged provides the layer blocks, plants, rocks and foliage (only blocks for VanillaLayerPlus).

Vanilla terrain generation is also supported (`layer_injection`) but disabled by default — the heuristic is less accurate and still being improved. You can enable `vanilla_noise_router_layer_injection` alongside it for significantly better results, using vanilla's own noise density functions (continents, erosion, ridges) instead of the slope-based heuristic.

## Features

- **RTF-based layer injection** — uses ReTerraForged's cell height data for realistic, gradient-based layer depth
- **Vanilla injection** — heightmap + noise-router fallback for non-RTF worlds (experimental)
- **Dual backend** — Conquest Reforged or VanillaLayerPlus
- **Plant handling** — converts vanilla plants to CR equivalents, or shifts them for VLP
- **Underwater layers** — waterlogged layer blocks on ocean and river floors
- **Rocks & foliage** — biome-weighted CR rock and foliage placement above layers
- **Structure awareness** — skip or clean up layers inside and around structure bounding boxes
- **NBT tree placement** — replaces vanilla worldgen trees with hand-crafted CR NBT structures
- **Snowy biome handling** — skip, improve, or convert snow layers to mapped equivalents

## Requirements

- Minecraft 1.20.1
- Fabric Loader 0.15.11+
- Fabric API 0.92.2+
- One of:
  - [Conquest Reforged](https://www.curseforge.com/minecraft/mc-mods/conquest-reforged) *(recommended)*
  - VanillaLayerPlus
- Strongly recommended: [ReTerraForged](https://github.com/racoonman2/ReTerraForged) You can use this [working fork](https://github.com/UF4OVER/ReTerraForged/releases/tag/0.0.6-fix2)

## Installation

1. Install Fabric Loader 0.15.11+ and Fabric API 0.92.2+ for Minecraft 1.20.1
2. Install Conquest Reforged (or VanillaLayerPlus)
3. Install ReTerraForged *(recommended)*
4. Place the mod JAR in your mods folder
5. Launch once to generate config files in `config/aronalayersgen/`

### NBT Trees (Only with Conquest Reforged)

The NBT tree feature (`cr_nbt_trees`) requires hand-crafted tree structures in `.nbt` format that are not bundled with the mod.

See **[nbt_trees_guide.md](scripts/nbt_trees_guide.md)** for step-by-step instructions on generating this folder from an existing schematic pack by quitefrank_ly.

Once generated, place the `nbt_trees/` folder at:
```
config/aronalayersgen/nbt_trees/
```

## Configuration

All settings live in `config/aronalayersgen/layer_config.json`.

> **Default setup:** The mod ships preconfigured for **RTF + Conquest Reforged** with all major features enabled. If you are not using RTF, set `rtf_layer_injection: false` and enable `layer_injection` instead.

---

### Layer Injection

| Key | Default | Description |
|-----|---------|-------------|
| `rtf_layer_injection` | `true` | RTF-based layer generation — recommended, requires ReTerraForged |
| `layer_injection` | `true` | Vanilla heightmap-based layer generation |
| `vanilla_noise_router_layer_injection` | `false` | Improve vanilla mode using NoiseRouter density functions instead of the slope heuristic. No effect when RTF is active |
| `injection_mode` | `POST_FEATURES` | `CARVERS` (before features) or `POST_FEATURES` (after structures, cleaner result) |

> **Vanilla mode note:** `layer_injection` is functional but less accurate than RTF injection — terrain heightmap heuristics produce inconsistent layer depth on complex terrain. Enabling `vanilla_noise_router_layer_injection` closes most of this gap. RTF remains the recommended path.

---

### Snowy Biomes

| Key | Default | Description |
|-----|---------|-------------|
| `skip_snowy_biomes` | `true` | Skip all layer placement in cold/snowy biomes |
| `improve_snowy_biomes` | `false` | Use vanilla snow layers in snowy biomes instead of skipping. Requires `skip_snowy_biomes: false` |
| `break_snow_layers_to_mapped_layers` | `false` | When a snow layer is broken, replace it with the mapped layer block for the block below, reduced by 1 |

---

### Plants & Decoration

| Key | Default | Description |
|-----|---------|-------------|
| `plant_injection` | `false` | Convert vanilla plants above layers to CR/VLP equivalents |
| `replace_sea_grass` | `false` | Also convert seagrass and tall_seagrass to CR equivalents |
| `tree_injection` | `false` | Allow trees to generate through layer blocks |
| `underwater_layers` | `false` | Place waterlogged layers on underwater surfaces |
| `replace_dirt_path` | `false` | Replace dirt_path with the mapped solid block when placing a layer on it |
| `place_wet_sand` | `false` | Use CR wet_sand_layer instead of sand_layer when the position is waterlogged or below sea level |

---

### Rocks

| Key | Default | Description |
|-----|---------|-------------|
| `place_rocks` | `false` | Place CR rock blocks above layers |
| `conquest_enhanced_rocks` | `false` | Per-biome weighted rocks using `cr_enhanced_rock_mappings.json`. Falls back to `cr_rock_mappings.json` for unregistered biomes |
| `chance_to_place_rocks` | `0.05` | Probability per eligible position (0.0–1.0) |
| `rock_density_mode` | `DECREASED` | `RANDOM` (uniform) or `DECREASED` (biased toward smaller rocks) |
| `rock_density_factor` | `4.0` | Bias strength for DECREASED mode — higher = more small rocks (~75/19/5/1% at 4.0) |

---

### Foliage

| Key | Default | Description |
|-----|---------|-------------|
| `place_extra_foliage` | `false` | Place CR foliage above layers |
| `conquest_enhanced_extra_foliage` | `false` | Per-biome weighted foliage using `cr_enhanced_extra_foliage_mappings.json` |
| `chance_to_place_extra_foliage` | `0.5` | Probability per eligible position (0.0–1.0) |

---

### NBT Trees

| Key | Default | Description |
|-----|---------|-------------|
| `cr_nbt_trees` | `false` | Replace vanilla worldgen trees with CR NBT structures. Requires the `nbt_trees/` folder — see [nbt_trees_guide.md](nbt_trees_guide.md) |
| `cr_nbt_trees_vanilla_fallback` | `true` | If no NBT variant is found for a sapling, let vanilla tree growth proceed. `false` suppresses vanilla growth entirely |

Tree species, biome assignments and sapling mappings are configured in `cr_nbt_trees.json`. NBT files live in `config/aronalayersgen/nbt_trees/<species>/`.

---

### Structure Awareness

| Key | Default | Description |
|-----|---------|-------------|
| `structure_injection` | `false` | Skip layers inside structure bounding boxes |
| `structure_injection_extra_bounds` | `false` | Expand structure bounding boxes outward by a buffer zone |
| `structure_injection_extra_bounds_distance` | `4` | Buffer radius in blocks |
| `cross_chunk_structure_detection` | `false` | Also check neighboring chunks for structures that start outside the current chunk. Disable during pre-generation with Chunky or similar |
| `structure_no_layers` | `false` | Remove layers where the structure changed the terrain heightmap. More precise than bounding-box detection |
| `structure_cleanup` | `false` | Remove layers under opaque structure blocks after generation |
| `enclosed_space_check` | `false` | Skip layers under solid ceilings (cave interiors, buildings). Requires `structure_injection` |
| `enclosed_space_height` | `7` | Height in blocks to scan upward for a ceiling |
| `second_pass_cleanup` | `false` | After structure detection removes layers, taper neighboring layer values to avoid abrupt edges |

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

| Key | Default | Description |
|-----|---------|-------------|
| `debug_logging` | `false` | Master switch — all per-category flags below require this to be `true` |
| `debug_log_chunk_init` | `false` | Chunk initialization |
| `debug_log_rtf` | `false` | RTF injection |
| `debug_log_structure` | `false` | Structure detection |
| `debug_log_skip_elevated` | `false` | Structure skip-elevated detection |
| `debug_log_structure_no_layers` | `false` | Heightmap-based structure detection |
| `debug_log_second_pass` | `false` | Second-pass cleanup |
| `debug_log_vanilla` | `false` | Vanilla injection |
| `debug_log_nbt_trees` | `false` | NBT tree placement |
| `debug_log_foliage` | `false` | Foliage placement |
| `debug_log_plants` | `false` | Plant conversion |
| `debug_log_rocks` | `false` | Rock placement |
| `debug_log_snow` | `false` | Snow layer handling |
| `debug_log_tree_soil` | `false` | Tree soil clearing |
| `debug_log_correction` | `false` | Layer value correction |

---

### Block Mapping Files

All files are auto-created in `config/aronalayersgen/` on first launch:

| File | Backend | Description |
|------|---------|-------------|
| `cr_block_mappings.json` | CR | Vanilla block → CR layer block |
| `vp_block_mappings.json` | VLP | Vanilla block → VLP layer block |
| `plant_mappings.json` | CR | Vanilla plant → CR plant |
| `cr_rock_mappings.json` | CR | Surface block → CR rock |
| `cr_extra_foliage_mappings.json` | CR | Surface block → CR foliage |
| `cr_enhanced_rock_mappings.json` | CR | Per-biome weighted rock mappings |
| `cr_enhanced_extra_foliage_mappings.json` | CR | Per-biome weighted foliage mappings |
| `cr_nbt_trees.json` | CR | NBT tree species, biomes and sapling mappings |
| `second_pass_cleanup_blocks.json` | Both | Blocks that trigger layer cleanup |
| `structure_elevation_blocks.json` | Both | Blocks that indicate structure-elevated terrain |

The active block mapping file (CR vs VLP) is chosen automatically based on which layer mod is installed.

## Building from Source

```
./gradlew build
```

The compiled JAR will be in `build/libs/`.

## License

MIT License

## Author

Arona74
