# Arona Layers Generator

A Fabric mod for Minecraft 1.20.1 that automatically generates terrain layer blocks during worldgen using ReTerraForged terrain data or vanilla heightmap analysis.

## Features

- **Worldgen Integration**: Layers are placed during chunk generation — no commands, no post-processing
- **ReTerraForged Support**: Uses RTF's height cell data for realistic, gradient-based layer depth distribution
- **Dual Backend Support**: Works with Conquest Reforged or VanillaLayerPlus for the actual layer blocks
- **Plant Handling**: Vanilla plants above layers are handled automatically per backend
  - Conquest Reforged: converts plants to CR equivalents
  - VanillaLayerPlus: shifts plants one block up (VP handles visual offset)
- **Underwater Support**: Optional waterlogged layers on ocean and river floors
- **Structure Awareness**: Optionally skips layer placement inside structure bounding boxes
- **Configurable**: JSON-based configuration in `config/aronalayersgen/`

## Requirements

- Minecraft 1.20.1
- Fabric Loader 0.15.11+
- Fabric API 0.92.2+
- One of the following layer mods:
  - [Conquest Reforged](https://www.curseforge.com/minecraft/mc-mods/conquest-reforged)
  - VanillaLayerPlus
- Strongly recommended: [ReTerraForged](https://github.com/TerraForgedMC/TerraForged) for best results

## Installation

1. Install Fabric Loader 0.15.11+ for Minecraft 1.20.1
2. Install Fabric API 0.92.2+
3. Install Conquest Reforged or VanillaLayerPlus
4. Optionally install ReTerraForged
5. Place this mod's JAR in your mods folder
6. Launch the game once to generate the config file at `config/aronalayersgen/layer_config.json`

## Configuration

All settings live in `config/aronalayersgen/layer_config.json`.

### Core Settings

| Key | Default | Description |
|-----|---------|-------------|
| `rtf_layer_injection` | `false` | Enable RTF-based layer generation (requires ReTerraForged) |
| `layer_injection` | `false` | Enable vanilla heightmap-based layer generation |
| `injection_mode` | `CARVERS` | When to inject: `CARVERS` (before features) or `POST_FEATURES` (after structures) |
| `skip_snowy_biomes` | `true` | Skip layer placement in cold/snowy biomes |
| `improve_snowy_biomes` | `false` | Use vanilla snow layers in snowy biomes instead of skipping |
| `underwater_layers` | `false` | Place waterlogged layers on underwater surfaces |

### Plant & Decoration Settings

| Key | Default | Description |
|-----|---------|-------------|
| `plant_injection` | `false` | Handle vanilla plants above layer blocks |
| `replace_sea_grass` | `false` | Include seagrass/tall seagrass in plant handling |
| `tree_injection` | `false` | Allow trees to grow through layer blocks |
| `place_rocks` | `false` | Place CR rock blocks above layers (CR only) |
| `place_extra_foliage` | `false` | Place CR foliage above layers (CR only) |

### Structure Settings

| Key | Default | Description |
|-----|---------|-------------|
| `structure_injection` | `false` | Skip layers inside structure bounding boxes |
| `structure_cleanup` | `false` | Remove layers under opaque structure blocks post-generation |
| `structure_no_layers` | `false` | Remove layers where structures modified the heightmap |
| `enclosed_space_check` | `false` | Skip layers in enclosed spaces (caves, buildings) |

### Block Mapping Files

Located in `config/aronalayersgen/` (created on first run) or bundled as defaults:

- **`vp_block_mappings.json`**: Vanilla block → VanillaLayerPlus layer block mappings (VLP only)
- **`cr_block_mappings.json`**: Vanilla block → Conquest Reforged layer block mappings (CR only)
- **`cr_plant_mappings.json`**: Vanilla plant → CR plant mappings (CR only)
- **`cr_rock_mappings.json`**: Surface block → CR rock block mappings (CR only)
- **`cr_extra_foliage_mappings.json`**: Surface block → CR extra foliage mappings (CR only)

The active block mapping file is chosen automatically based on which layer mod is installed.

## Building from Source

```
./gradlew build
```

The compiled JAR will be in `build/libs/`.

## License

MIT License

## Author

Arona74
