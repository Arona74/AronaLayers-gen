#!/usr/bin/env python3
"""
Convert WorldEdit .schem files (Sponge Schematic v2) to Minecraft NBT structure templates.
Skips files that already exist in the output directory.
"""

import re
from pathlib import Path

import nbtlib

SCHEM_DIR = Path(__file__).resolve().parent
NBT_DIR   = SCHEM_DIR.parent / "nbt_trees"

# Longest prefixes first so 'norway_spruce' matches before 'norway'
SPECIES = ['norway_spruce', 'aspen', 'beech', 'larch', 'beach', 'oak', 'hawthorn', 'holly']

AIR_NAMES = {'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'}


def get_species(stem: str) -> str:
    for sp in SPECIES:
        if stem.startswith(sp + '_'):
            return sp
    return stem.split('_')[0]


def strip_leading_zero(stem: str) -> str:
    """oak_g_xs_01 -> oak_g_xs_1"""
    return re.sub(r'_0*(\d+)$', r'_\1', stem)


def decode_varints(data: bytes) -> list:
    result = []
    i = 0
    while i < len(data):
        value = 0
        shift = 0
        while True:
            b = data[i]; i += 1
            value |= (b & 0x7F) << shift
            if not (b & 0x80):
                break
            shift += 7
        result.append(value)
    return result


def parse_blockstate(s: str):
    """'minecraft:oak_log[axis=y]' -> ('minecraft:oak_log', {'axis': 'y'})"""
    m = re.match(r'^([^\[]+)(?:\[([^\]]*)\])?$', s)
    name = m.group(1)
    props = {}
    if m.group(2):
        for kv in m.group(2).split(','):
            k, v = kv.split('=', 1)
            props[k] = v
    return name, props


def convert(schem_path: Path, nbt_path: Path):
    schem = nbtlib.load(str(schem_path))

    width  = int(schem['Width'])
    height = int(schem['Height'])
    length = int(schem['Length'])
    data_version = int(schem.get('DataVersion', nbtlib.Int(3465)))

    # Palette: blockstate string -> int index
    palette_map = {k: int(v) for k, v in schem['Palette'].items()}
    rev_palette = {v: k for k, v in palette_map.items()}

    block_indices = decode_varints(bytes(schem['BlockData']))

    out_palette_list = []   # [(name, props_dict), ...]
    out_palette_map  = {}   # blockstate_str -> output index
    out_blocks       = []   # [(x, y, z, state_idx), ...]

    for flat_idx, block_idx in enumerate(block_indices):
        bs_str = rev_palette[block_idx]
        name, props = parse_blockstate(bs_str)
        if name in AIR_NAMES:
            continue

        # YZX order in Sponge schematics
        y   = flat_idx // (width * length)
        rem = flat_idx  % (width * length)
        z   = rem // width
        x   = rem  % width

        if bs_str not in out_palette_map:
            out_palette_map[bs_str] = len(out_palette_list)
            out_palette_list.append((name, props))

        out_blocks.append((x, y, z, out_palette_map[bs_str]))

    # Build palette NBT list
    palette_nbt = nbtlib.List[nbtlib.Compound]()
    for name, props in out_palette_list:
        entry = nbtlib.Compound({'Name': nbtlib.String(name)})
        if props:
            entry['Properties'] = nbtlib.Compound(
                {k: nbtlib.String(v) for k, v in props.items()}
            )
        palette_nbt.append(entry)

    # Build blocks NBT list
    blocks_nbt = nbtlib.List[nbtlib.Compound]()
    for x, y, z, state_idx in out_blocks:
        blocks_nbt.append(nbtlib.Compound({
            'pos':   nbtlib.List[nbtlib.Int]([nbtlib.Int(x), nbtlib.Int(y), nbtlib.Int(z)]),
            'state': nbtlib.Int(state_idx),
        }))

    out_file = nbtlib.File({
        'size':        nbtlib.List[nbtlib.Int]([nbtlib.Int(width), nbtlib.Int(height), nbtlib.Int(length)]),
        'palette':     palette_nbt,
        'blocks':      blocks_nbt,
        'entities':    nbtlib.List[nbtlib.Compound](),
        'DataVersion': nbtlib.Int(data_version),
    })

    nbt_path.parent.mkdir(parents=True, exist_ok=True)
    out_file.save(str(nbt_path), gzipped=True)
    print(f"  {schem_path.name:40s} -> {nbt_path.relative_to(NBT_DIR)}")


def main():
    schem_files = sorted(SCHEM_DIR.glob('*.schem'))
    print(f"Found {len(schem_files)} .schem files in {SCHEM_DIR.name}")
    print()

    converted = 0
    skipped   = 0
    errors    = 0

    for schem_path in schem_files:
        stem    = schem_path.stem
        species = get_species(stem)
        out_stem = strip_leading_zero(stem)
        nbt_path = NBT_DIR / species / (out_stem + '.nbt')

        if nbt_path.exists():
            skipped += 1
            continue

        try:
            convert(schem_path, nbt_path)
            converted += 1
        except Exception as e:
            print(f"  ERROR {schem_path.name}: {e}")
            errors += 1

    print()
    print(f"Done: {converted} converted, {skipped} already existed, {errors} errors")


if __name__ == '__main__':
    main()
