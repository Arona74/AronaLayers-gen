"""Build a mojmap(versionA) -> mojmap(versionB) rename map.

Intermediary ids are stable across Minecraft versions, so joining
  intermediary -> mojmap
for two versions and matching on the intermediary id yields exactly what
Mojang renamed between them.

Usage: python version_diff.py <interA.jar> <mojA.txt> <interB.jar> <mojB.txt> <out.json>
"""
import json
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_map import parse_proguard, parse_tiny, read_tiny  # noqa: E402


def inter_to_moj(inter_jar, moj_txt):
    i_classes, i_methods, i_fields = parse_tiny(read_tiny(inter_jar))
    m_classes, m_methods, m_fields = parse_proguard(moj_txt)

    classes = {}          # intermediary class -> mojmap class
    for off, inter in i_classes.items():
        moj = m_classes.get(off)
        if moj:
            classes[inter] = moj

    methods = {}          # (intermediary owner, intermediary method) -> mojmap name
    for (own_off, name_off, desc_off), name_int in i_methods.items():
        own_int = i_classes.get(own_off)
        moj = m_methods.get((own_off, name_off, desc_off))
        if own_int and moj:
            methods[(own_int, name_int)] = moj

    fields = {}
    for (own_off, name_off), name_int in i_fields.items():
        own_int = i_classes.get(own_off)
        moj = m_fields.get((own_off, name_off))
        if own_int and moj:
            fields[(own_int, name_int)] = moj
    return classes, methods, fields


def main():
    ia, ma, ib, mb, out = sys.argv[1:6]
    ca, mea, fa = inter_to_moj(ia, ma)
    cb, meb, fb = inter_to_moj(ib, mb)

    class_ren = {}
    for inter, a in ca.items():
        b = cb.get(inter)
        if b and a != b:
            class_ren[a] = b

    method_ren = {}       # "OwnerSimpleA#nameA" -> nameB
    method_any = {}       # nameA -> {nameB}
    for key, a in mea.items():
        b = meb.get(key)
        if b and a != b:
            owner_moj = ca.get(key[0], key[0]).split("/")[-1]
            method_ren[f"{owner_moj}#{a}"] = b
            method_any.setdefault(a, set()).add(b)

    field_ren = {}
    for key, a in fa.items():
        b = fb.get(key)
        if b and a != b:
            owner_moj = ca.get(key[0], key[0]).split("/")[-1]
            field_ren[f"{owner_moj}#{a}"] = b

    res = {
        "classes": class_ren,
        "methods_qualified": method_ren,
        "methods_unique": {k: next(iter(v)) for k, v in method_any.items() if len(v) == 1},
        "methods_ambiguous": {k: sorted(v) for k, v in method_any.items() if len(v) > 1},
        "fields_qualified": field_ren,
    }
    with open(out, "w", encoding="utf-8") as f:
        json.dump(res, f, indent=1)
    print(f"class renames:      {len(class_ren)}")
    print(f"method renames:     {len(method_ren)} (unique by name: {len(res['methods_unique'])}, ambiguous: {len(res['methods_ambiguous'])})")
    print(f"field renames:      {len(field_ren)}")


if __name__ == "__main__":
    main()
