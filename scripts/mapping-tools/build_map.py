"""Build a yarn -> Mojang-mappings rename dictionary for a given MC version.

Chain: yarn(named) <- intermediary -> official(obf) -> mojmap(deobf)
  - yarn v2 tiny:         ns0=intermediary, ns1=named
  - intermediary v2 tiny: ns0=official,     ns1=intermediary
  - mojmap ProGuard txt:  deobf -> obf
"""
import json
import sys
import zipfile
from collections import defaultdict


def read_tiny(jar_path):
    z = zipfile.ZipFile(jar_path)
    name = [n for n in z.namelist() if n.endswith(".tiny")][0]
    with z.open(name) as f:
        return f.read().decode("utf-8").splitlines()


def parse_tiny(lines):
    """Return (classes, methods, fields) keyed ns0 -> ns1."""
    classes = {}
    methods = {}   # (ns0_owner, ns0_name, ns0_desc) -> ns1_name
    fields = {}    # (ns0_owner, ns0_name) -> ns1_name
    owner = None
    for line in lines:
        if not line or line.startswith("tiny\t"):
            continue
        parts = line.split("\t")
        if parts[0] == "c":
            owner = parts[1]
            classes[parts[1]] = parts[2]
        elif len(parts) >= 5 and parts[0] == "" and parts[1] == "m":
            methods[(owner, parts[3], parts[2])] = parts[4]
        elif len(parts) >= 5 and parts[0] == "" and parts[1] == "f":
            fields[(owner, parts[3])] = parts[4]
    return classes, methods, fields


PRIMS = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
         "int": "I", "long": "J", "float": "F", "double": "D"}


def type_to_desc(t, deobf_to_obf):
    """Convert a ProGuard (deobfuscated) Java type to an obfuscated JVM descriptor."""
    arr = 0
    while t.endswith("[]"):
        arr += 1
        t = t[:-2]
    if t in PRIMS:
        base = PRIMS[t]
    else:
        internal = t.replace(".", "/")
        base = "L" + deobf_to_obf.get(internal, internal) + ";"
    return "[" * arr + base


def parse_proguard(path):
    """Return (classes obf->deobf,
               methods (obf_owner, obf_name, obf_desc) -> deobf_name,
               fields  (obf_owner, obf_name) -> deobf_name).
    ProGuard lists deobf -> obf, so we invert. Two passes: classes first so
    descriptors can be obfuscated correctly."""
    classes = {}
    raw = []
    cur_obf = None
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            line = line.rstrip("\n")
            if not line.startswith("    "):
                if "->" in line and line.endswith(":"):
                    deobf, obf = line[:-1].split(" -> ")
                    deobf = deobf.strip().replace(".", "/")
                    obf = obf.strip().replace(".", "/")
                    classes[obf] = deobf
                    cur_obf = obf
            else:
                if "->" in line and cur_obf is not None:
                    raw.append((cur_obf, line.strip()))

    deobf_to_obf = {v: k for k, v in classes.items()}
    methods = {}
    fields = {}
    for owner, line in raw:
        left, obf_name = line.rsplit(" -> ", 1)
        while left and left[0].isdigit() and ":" in left:
            left = left.split(":", 1)[1]
        if "(" in left:
            head, args = left.split("(", 1)
            args = args.rstrip(")")
            ret_type, deobf_name = head.rsplit(" ", 1)
            arg_list = [a for a in args.split(",") if a]
            desc = ("(" + "".join(type_to_desc(a, deobf_to_obf) for a in arg_list)
                    + ")" + type_to_desc(ret_type, deobf_to_obf))
            methods[(owner, obf_name, desc)] = deobf_name
        else:
            fields[(owner, obf_name)] = left.rsplit(" ", 1)[-1]
    return classes, methods, fields


def main(yarn_jar, inter_jar, mojmap_txt, out_json):
    y_classes, y_methods, y_fields = parse_tiny(read_tiny(yarn_jar))
    i_classes, i_methods, i_fields = parse_tiny(read_tiny(inter_jar))
    m_classes, m_methods, m_fields = parse_proguard(mojmap_txt)

    # intermediary -> official (invert i_classes: official -> intermediary)
    inter_to_official = {v: k for k, v in i_classes.items()}

    class_map = {}          # yarn FQN -> mojmap FQN
    inter_to_yarn = y_classes
    for inter, yarn in inter_to_yarn.items():
        official = inter_to_official.get(inter)
        if official is None:
            continue
        moj = m_classes.get(official)
        if moj:
            class_map[yarn] = moj

    # members: build intermediary member -> official member, then -> mojmap
    inter_member_to_official = {}
    for (own_off, name_off, desc_off), name_int in i_methods.items():
        own_int = i_classes.get(own_off)
        if own_int:
            inter_member_to_official[(own_int, name_int)] = (own_off, name_off, desc_off)
    inter_field_to_official = {}
    for (own_off, name_off), name_int in i_fields.items():
        own_int = i_classes.get(own_off)
        if own_int:
            inter_field_to_official[(own_int, name_int)] = (own_off, name_off)

    method_map = defaultdict(set)   # yarn simple name -> {mojmap names}
    method_map_q = {}               # "yarnFQN#yarnName" -> mojmap name
    for (own_int, name_int, _desc), name_yarn in y_methods.items():
        off = inter_member_to_official.get((own_int, name_int))
        if not off:
            continue
        moj = m_methods.get(off)
        if not moj:
            continue
        own_yarn = y_classes.get(own_int)
        if own_yarn:
            method_map_q[f"{own_yarn}#{name_yarn}"] = moj
            method_map[name_yarn].add(moj)

    field_map = defaultdict(set)
    field_map_q = {}
    for (own_int, name_int), name_yarn in y_fields.items():
        off = inter_field_to_official.get((own_int, name_int))
        if not off:
            continue
        moj = m_fields.get(off)
        if not moj:
            continue
        own_yarn = y_classes.get(own_int)
        if own_yarn:
            field_map_q[f"{own_yarn}#{name_yarn}"] = moj
            field_map[name_yarn].add(moj)

    out = {
        "classes": class_map,
        "methods_qualified": method_map_q,
        "methods_ambiguous": {k: sorted(v) for k, v in method_map.items() if len(v) > 1},
        "methods_unique": {k: next(iter(v)) for k, v in method_map.items() if len(v) == 1},
        "fields_qualified": field_map_q,
        "fields_unique": {k: next(iter(v)) for k, v in field_map.items() if len(v) == 1},
    }
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)

    print(f"classes:            {len(class_map)}")
    print(f"methods qualified:  {len(method_map_q)}")
    print(f"methods unique:     {len(out['methods_unique'])}")
    print(f"methods ambiguous:  {len(out['methods_ambiguous'])}")
    print(f"fields qualified:   {len(field_map_q)}")


if __name__ == "__main__":
    main(*sys.argv[1:5])
