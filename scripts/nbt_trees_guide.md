# HOW-TO get your nbt_trees folder

This converts a WorldEdit schematic tree pack into the `.nbt` files the mod reads from your config folder.

## Prerequisites

- **Python 3.8+** — [python.org](https://www.python.org/downloads/)
- **nbtlib** — open a terminal and run:
  ```
  pip install nbtlib
  ```

## Steps

### 1. Download the schematic trees pack by quitefrank_ly

Download and extract the pack: [[QF] Trees Repository - Conquest Reforged](https://www.planetminecraft.com/project/conquest-reforged-larch-tree-bundle)

You should end up with a folder called `Trees/` containing many `.schem` files.

### 2. Download the conversion script

Download `convert_schematics.py` and place it directly inside the `Trees/` folder:

```
Trees/
    convert_schematics.py
    aspen_01.schem
    oak_g_xs_1.schem
    ...
```

### 3. Run the script

Open a terminal inside the `Trees/` folder and run:

```
python convert_schematics.py
```

The script will create an `nbt_trees/` folder next to `Trees/`:

```
Trees/
nbt_trees/
    aspen/
    beech/
    oak/
    ...
```

### 4. Copy to your instance config

Copy the `nbt_trees/` folder into your instance's config folder:

```
.minecraft/config/aronalayersgen/nbt_trees/
```

That's it — launch the game and the trees will be active.

---

> **Already ran it before?** Re-running the script skips files that already exist in `nbt_trees/`, so you can safely re-run it after downloading new schematics.
