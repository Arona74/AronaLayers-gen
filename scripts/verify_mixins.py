"""Check that every mixin target in a built jar actually got remapped.

Loom 1.17 has no refmap: it rewrites the mixin annotations in place. When it
cannot resolve a target it silently leaves the developer-mappings name behind,
the build still succeeds, and the mod dies at mixin-apply time with
"could not find any targets matching '<name>'".

So: every method= value must be <init>, an intermediary method_NNNN, or a name
that is genuinely never obfuscated (java.* overrides). Anything else is a target
that failed to resolve.

Usage: python verify_mixins.py <module_dir> [<module_dir> ...]
"""
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile

# names inherited from the JDK, never obfuscated, so they stay as-is legitimately
NEVER_OBFUSCATED = {"close", "toString", "equals", "hashCode", "run", "get", "accept"}

ok_re = re.compile(r"^(?:<init>|<clinit>|method_\d+)(?:\(.*)?$")


def check(jar):
    z = zipfile.ZipFile(jar)
    cfg = json.loads(z.read("aronalayersgen.mixins.json").decode())
    tmp = tempfile.mkdtemp()
    problems = []
    checked = 0
    for cls in cfg["mixins"]:
        entry = f"io/arona74/aronalayersgen/mixin/{cls}.class"
        if entry not in z.namelist():
            problems.append((cls, "class missing from jar"))
            continue
        p = os.path.join(tmp, cls + ".class")
        open(p, "wb").write(z.read(entry))
        out = subprocess.run(["javap", "-v", "-p", p], capture_output=True, text=True).stdout
        for m in re.finditer(r'method=\[([^\]]*)\]', out):
            for raw in m.group(1).split(","):
                name = raw.strip().strip('"')
                if not name:
                    continue
                checked += 1
                if ok_re.match(name) or name.split("(")[0] in NEVER_OBFUSCATED:
                    continue
                problems.append((cls, f'method="{name}" not remapped'))
    return cfg["mixins"], checked, problems


def main():
    bad = 0
    for mod in sys.argv[1:]:
        jars = [x for x in glob.glob(os.path.join(mod, "build/libs/*.jar")) if "sources" not in x]
        if not jars:
            print(f"{mod}: no jar built")
            continue
        jar = max(jars, key=os.path.getmtime)
        mixins, checked, problems = check(jar)
        status = "OK" if not problems else "FAILED"
        print(f"{mod:10s} {os.path.basename(jar):44s} {len(mixins)} mixins, {checked} targets: {status}")
        for cls, why in problems:
            print(f"    !! {cls}: {why}")
            bad += 1
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
