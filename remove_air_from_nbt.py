#!/usr/bin/env python3
"""Remove air blocks from Minecraft .nbt structure files in a directory."""

import argparse
import glob
import os
import sys

import nbtlib


AIR_BLOCKS = {"minecraft:air", "air"}


def remove_air(file_path: str, dry_run: bool = False) -> int:
    """Remove air blocks from one .nbt file. Returns number of air blocks removed."""
    f = nbtlib.load(file_path)

    palette = f["palette"]
    blocks = f["blocks"]

    # Collect palette indices that are air
    air_indices = {
        i for i, entry in enumerate(palette)
        if str(entry["Name"]) in AIR_BLOCKS
    }

    if not air_indices:
        return 0

    # Filter out blocks that reference air palette entries
    original_count = len(blocks)
    filtered_blocks = [b for b in blocks if int(b["state"]) not in air_indices]
    removed = original_count - len(filtered_blocks)

    if dry_run:
        return removed

    # Rebuild palette without air entries, tracking index remapping
    new_palette = []
    remap = {}
    for old_idx, entry in enumerate(palette):
        if old_idx not in air_indices:
            remap[old_idx] = len(new_palette)
            new_palette.append(entry)

    # Remap state indices in the remaining blocks
    for b in filtered_blocks:
        b["state"] = nbtlib.Int(remap[int(b["state"])])

    f["palette"] = nbtlib.List[nbtlib.Compound](new_palette)
    f["blocks"] = nbtlib.List[nbtlib.Compound](filtered_blocks)
    f.save(file_path)

    return removed


def process_directory(directory: str, dry_run: bool = False) -> None:
    pattern = os.path.join(directory, "*.nbt")
    files = sorted(glob.glob(pattern))

    if not files:
        print(f"No .nbt files found in: {directory}")
        return

    total_removed = 0
    modified = 0

    for fp in files:
        removed = remove_air(fp, dry_run=dry_run)
        name = os.path.basename(fp)
        if removed:
            total_removed += removed
            modified += 1
            tag = "[dry-run] would remove" if dry_run else "removed"
            print(f"  {name}: {tag} {removed} air block(s)")
        else:
            print(f"  {name}: ok (no air)")

    action = "would be modified" if dry_run else "modified"
    print(f"\n{modified}/{len(files)} files {action}, {total_removed} air block(s) total")


def main():
    parser = argparse.ArgumentParser(description="Remove air blocks from .nbt structure files")
    parser.add_argument("directory", help="Directory containing .nbt files")
    parser.add_argument("--dry-run", action="store_true", help="Report without modifying files")
    args = parser.parse_args()

    if not os.path.isdir(args.directory):
        print(f"Error: not a directory: {args.directory}", file=sys.stderr)
        sys.exit(1)

    process_directory(args.directory, dry_run=args.dry_run)


if __name__ == "__main__":
    main()
