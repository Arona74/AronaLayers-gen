"""Rewrite yarn-named Java sources to Mojang mappings.

Single simultaneous substitution pass so that colliding renames
(yarn Properties -> BlockStateProperties, yarn Settings -> Properties)
cannot corrupt each other.

Usage: python rewrite.py <map.json> <repo_root> [--apply]
"""
import json
import re
import sys
import glob
import os

DRY = "--apply" not in sys.argv


def resolve(classes, fqn_dotted):
    """Resolve a dotted yarn name (possibly nested) to its dictionary key."""
    parts = fqn_dotted.split(".")
    for split in range(len(parts), 0, -1):
        base = "/".join(parts[:split])
        rest = parts[split:]
        cand = base
        for r in rest:
            if cand in classes or (cand + "$" + r) in classes:
                cand = cand + "$" + r
        if cand in classes:
            return cand
        if base in classes:
            return base
    return None


def key_to_dotted(key):
    return key.replace("/", ".").replace("$", ".")


def simple_of(key):
    """Simple name as written in source: outer.Inner for nested."""
    tail = key.split("/")[-1]
    return tail.replace("$", ".")


def main():
    map_path, root = sys.argv[1], sys.argv[2]
    m = json.load(open(map_path, encoding="utf-8"))
    classes = m["classes"]
    mq = m["methods_qualified"]
    munique = m["methods_unique"]

    files = []
    for pat in ("src/main/java/**/*.java",
                "v1_20_1/src/**/*.java",
                "v1_21_1/src/**/*.java"):
        files += glob.glob(os.path.join(root, pat), recursive=True)

    total_changes = 0
    unmapped_methods = set()

    for fp in sorted(files):
        txt = open(fp, encoding="utf-8").read()
        orig = txt

        # ---- collect yarn classes referenced by this file ----
        refs = set(re.findall(r"\b(net\.minecraft\.[A-Za-z0-9_.]+)", txt))
        # imports give us the simple names in scope
        imports = re.findall(r"^\s*import\s+(net\.minecraft\.[A-Za-z0-9_.]+)\s*;",
                             txt, re.M)

        subs = {}   # literal source token -> replacement

        for ref in refs:
            k = resolve(classes, ref)
            if not k:
                continue
            moj = classes[k]
            # dotted yarn text that actually resolved
            yarn_dotted = key_to_dotted(k)
            subs[yarn_dotted] = key_to_dotted(moj)

        for imp in imports:
            k = resolve(classes, imp)
            if not k:
                continue
            moj = classes[k]
            y_simple = simple_of(k)
            m_simple = simple_of(moj)
            if y_simple != m_simple:
                subs[y_simple] = m_simple

        # ---- descriptor strings inside mixin annotations ----
        for d in set(re.findall(r"L(net/minecraft/[A-Za-z0-9_/$]+);", txt)):
            if d in classes:
                subs["L" + d + ";"] = "L" + classes[d] + ";"

        if not subs:
            continue

        # ---- single simultaneous pass, longest token first ----
        tokens = sorted(subs, key=len, reverse=True)
        pattern = re.compile(
            "|".join(r"(?<![\w.$])" + re.escape(t) + r"(?![\w$])" if not t.startswith("L")
                     else re.escape(t)
                     for t in tokens))
        txt = pattern.sub(lambda mo: subs[mo.group(0)], txt)

        # ---- mixin method= names ----
        target = re.search(r"@Mixin\(\s*([A-Za-z0-9_.]+)\.class", orig)
        tgt_key = None
        if target:
            for imp in imports:
                if imp.endswith("." + target.group(1)) or simple_of(resolve(classes, imp) or "") == target.group(1):
                    tgt_key = resolve(classes, imp)
                    break

        # Mixin method= names are deliberately NOT rewritten here.
        # methods_qualified is keyed owner#name with no descriptor, so overloads
        # collapse: TreeFeature#generate yields doPlace, but the mixin actually
        # targets place(FeaturePlaceContext). These are few, so they are
        # resolved explicitly against the mapped jar instead of guessed.
        for mo in re.finditer(r'method\s*=\s*"([A-Za-z0-9_<>]+)', orig):
            nm = mo.group(1)
            if not nm.startswith("<"):
                unmapped_methods.add(f"{os.path.basename(fp)}: {nm}")

        if txt != orig:
            total_changes += 1
            if not DRY:
                open(fp, "w", encoding="utf-8", newline="").write(txt)

    print(("DRY RUN — " if DRY else "APPLIED — ") + f"{total_changes} files changed")
    if unmapped_methods:
        print(f"\nmixin method names needing manual check ({len(unmapped_methods)}):")
        for u in sorted(unmapped_methods):
            print("   ", u)


if __name__ == "__main__":
    main()
