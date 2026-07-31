"""Parse javac 'cannot find symbol' errors and propose yarn->mojmap member renames.

javac gives us both the symbol and its owning type:

    Foo.java:12: error: cannot find symbol
            world.isClient()
                 ^
      symbol:   method isClient()
      location: variable world of type Level

We invert the class dictionary (Level -> World), then look up
net/minecraft/world/World#isClient in the member maps.

Usage: python suggest.py <map.json> <compile.log> [--apply <repo_root>]
"""
import json
import re
import sys
import os
from collections import defaultdict

map_path, log_path = sys.argv[1], sys.argv[2]
apply_root = None
if "--apply" in sys.argv:
    apply_root = sys.argv[sys.argv.index("--apply") + 1]

m = json.load(open(map_path, encoding="utf-8"))
classes = m["classes"]                     # yarn/slash -> mojmap/slash
mq = m["methods_qualified"]                # "yarnOwner#name" -> mojName
fq = m["fields_qualified"]
munique = m["methods_unique"]
funique = m["fields_unique"]

# mojmap simple name -> [yarn owner slash names]
moj_simple_to_yarn = defaultdict(list)
for y, mo in classes.items():
    moj_simple_to_yarn[mo.split("/")[-1].split("$")[-1]].append(y)

log = open(log_path, encoding="utf-8", errors="replace").read()

# Each error block: file:line, symbol kind+name, optional location type.
# Windows paths contain a drive colon, and `location:` appears either as
# "class Foo" / "interface Foo" or as "variable x of type Foo" -- or not at all.
block_re = re.compile(
    r"^(?P<file>(?:[A-Za-z]:)?[^\n]*?\.java):(?P<line>\d+): error: cannot find symbol[^\n]*\n"
    r"(?:[^\n]*\n){0,2}?"
    r"\s*symbol:\s+(?P<kind>method|variable|class)\s+(?P<name>[A-Za-z0-9_]+)[^\n]*\n"
    r"(?:\s*location:\s+(?:(?:class|interface|enum)\s+(?P<type1>[\w.$]+)"
    r"|[^\n]*?of type\s+(?P<type2>[\w.$]+)))?",
    re.M)

# Inherited members: javac names the subclass, but the mapping lives on the
# declaring supertype. Each verified by querying the declaring class directly.
# Keyed by the mojmap type javac reports, for names whose mapping depends on the
# owner: ModifiableWorld.setBlockState -> setBlock, but ChunkAccess.setBlockState
# keeps its name. A name-only override would corrupt the latter.
INHERITED_BY_TYPE = {
    "WorldGenLevel#setBlockState": "setBlock",
    "ServerLevelAccessor#setBlockState": "setBlock",
    "LevelAccessor#setBlockState": "setBlock",
    "ChunkAccess#getTopY": "getMaxBuildHeight",
    "LevelChunk#getTopY": "getMaxBuildHeight",
    "BlockState#isReplaceable": "canBeReplaced",
    "WorldGenLevel#getRegistryManager": "registryAccess",
    "LevelChunk#sampleHeightmap": "getHeight",
    "ChunkAccess#sampleHeightmap": "getHeight",
}

INHERITED = {
    "get": "getValue",
    "contains": "hasProperty",
    "with": "setValue",
    "isOf": "is",
    "getBottomY": "getMinBuildHeight",
    "getHeightmap": "getOrCreateHeightmapUnprimed",
    "getBiomeForNoiseGen": "getNoiseBiome",
    "getStructureReferences": "getReferencesForStructure",
    "getBlockPos": "blockPosition",
    "hasPermissionLevel": "hasPermission",
    "getValues": "getPossibleValues",
    "canPlaceAt": "canSurvive",
}

proposals = []                 # (file, line, kind, name, newname)
unresolved = defaultdict(set)

for mo in block_re.finditer(log):
    f = mo.group("file")
    kind = mo.group("kind")
    name = mo.group("name")
    typ = (mo.group("type1") or mo.group("type2") or "").split(".")[-1].split("$")[-1]

    cands = set()
    if typ:
        for yowner in moj_simple_to_yarn.get(typ, []):
            hit = (mq if kind == "method" else fq).get(f"{yowner}#{name}")
            if hit:
                cands.add(hit)
    if not cands and f"{typ}#{name}" in INHERITED_BY_TYPE:
        cands.add(INHERITED_BY_TYPE[f"{typ}#{name}"])
    if not cands and name in INHERITED:
        cands.add(INHERITED[name])
    if not cands:
        u = (munique if kind == "method" else funique).get(name)
        if u:
            cands.add(u)

    if len(cands) == 1:
        proposals.append((f, int(mo.group("line")), kind, name, next(iter(cands))))
    else:
        unresolved[f].add((kind, name, typ, tuple(sorted(cands))))

by_file = defaultdict(set)
for f, _ln, kind, old, new in proposals:
    by_file[f].add((kind, old, new))
print(f"resolved proposals: {len(proposals)} occurrences   files: {len(by_file)}")
for f in sorted(by_file):
    print(f"\n{os.path.basename(f)}")
    for kind, old, new in sorted(by_file[f]):
        print(f"    {kind:8s} {old}  ->  {new}")

if unresolved:
    print(f"\n--- UNRESOLVED ({sum(len(v) for v in unresolved.values())}) ---")
    for f in sorted(unresolved):
        for kind, name, typ, cands in sorted(unresolved[f]):
            print(f"    {os.path.basename(f):34s} {kind:8s} {name:26s} on {typ or '?':22s} {list(cands)}")

if apply_root:
    # Line-targeted: replace only on the exact line javac flagged, so a rename
    # like get -> getValue cannot leak onto an unrelated Map.get on another line.
    per_file = defaultdict(list)
    for f, ln, _kind, old, new in proposals:
        per_file[f].append((ln, old, new))

    changed = edits = skipped = 0
    for f, items in per_file.items():
        path = f if os.path.isabs(f) else os.path.join(apply_root, f)
        if not os.path.exists(path):
            continue
        lines = open(path, encoding="utf-8").read().split("\n")
        dirty = False
        for ln, old, new in items:
            if not (1 <= ln <= len(lines)):
                continue
            # Members are reached as receiver.name(...), so a preceding dot must
            # be allowed here (unlike class-name rewriting, which excludes it).
            pat = re.compile(r"(?<![\w$])" + re.escape(old) + r"(?![\w$])")
            hits = pat.findall(lines[ln - 1])
            if len(hits) != 1:
                skipped += 1          # ambiguous on this line; leave for next pass
                continue
            lines[ln - 1] = pat.sub(new, lines[ln - 1])
            dirty = True
            edits += 1
        if dirty:
            open(path, "w", encoding="utf-8", newline="").write("\n".join(lines))
            changed += 1
    print(f"\nAPPLIED {edits} line edits across {changed} files"
          f"{f' ({skipped} ambiguous lines skipped)' if skipped else ''}")
