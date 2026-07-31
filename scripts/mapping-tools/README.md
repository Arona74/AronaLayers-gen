# Mapping tools

One-off scripts used to port this mod across Minecraft versions. They are kept because the
same job recurs — the sibling mod AronaLayers-extras needs exactly this, and every future
Minecraft version will too.

They are **development aids, not part of the build**. Expect to read and adapt them rather
than run them blind.

## Why these exist

Two problems make a multi-version port tedious by hand:

1. **Renames.** Between any two Minecraft versions, hundreds of classes and thousands of
   methods change name. Guessing them one compiler error at a time is slow and wrong.
2. **Mapping migration.** Moving from yarn to Mojang mappings renames essentially every
   Minecraft symbol in the codebase at once.

Both are solvable exactly rather than approximately, because **intermediary ids are stable
across Minecraft versions**. Joining two mapping sets on intermediary gives the real answer.

## The scripts

### `build_map.py` — yarn -> Mojang mappings, for one version

```
python build_map.py <yarn-vN-v2.jar> <intermediary-vN-v2.jar> <mojmap-vN.txt> <out.json>
```

Chains `yarn(named) <- intermediary -> official(obf) -> mojmap(named)` and writes a
dictionary of class, method and field renames.

Inputs:
- yarn + intermediary tiny-v2 jars from `maven.fabricmc.net`
- Mojang `client_mappings` from the piston manifest (`launchermeta.mojang.com`)

**Note:** ProGuard methods are keyed by `(owner, name, descriptor)`. Keying by name alone
silently drops about two thirds of entries, because obfuscated names are heavily
overloaded. That bug cost real time; do not "simplify" it away.

### `version_diff.py` — Mojang(A) -> Mojang(B), between two versions

```
python version_diff.py <interA.jar> <mojA.txt> <interB.jar> <mojB.txt> <out.json>
```

Produces exactly what Mojang renamed between two versions. For 1.21.1 -> 1.21.11 this was
714 class and 2901 method renames.

**Does not work for 26.x**, which ships deobfuscated and has no intermediary — nothing
stable exists to join on. That jump has to be compiler-driven, with `javap` against the
Minecraft jar for anything ambiguous.

### `rewrite.py` — apply class renames to sources

```
python rewrite.py <map.json> <repo_root> [--apply]
```

Rewrites imports, fully-qualified references and simple type names. Defaults to a dry run.

Uses a **single simultaneous substitution pass**, deliberately: sequential replacement
corrupts colliding renames. `Properties -> BlockStateProperties` and `Settings -> Properties`
both exist, and running them one after another produces nonsense.

It does **not** touch mixin `method=` strings. Those are keyed by name without a
descriptor, so overloads collapse — `TreeFeature#generate` resolves to `doPlace` when the
mixin actually targets `place`. Resolve those against the jar by hand.

### `suggest.py` — compiler-driven member renames

```
python suggest.py <map.json> <compile.log> [--apply <repo_root>]
```

Parses javac `cannot find symbol` errors, which carry both the symbol and its owning type,
inverts the class map to find the yarn owner, and looks the member up.

Edits are **line-targeted** — only the exact line javac flagged. This matters: a global
`get -> getValue` would hit `Map.get`. Inherited members need the declaring supertype, so
there is a small hand-verified override table near the top; extend it as needed.

### `maxerrs.gradle`

```
./gradlew :module:compileJava --init-script scripts/mapping-tools/maxerrs.gradle
```

Lifts javac's 100-error cap. Without it the true error count is hidden and you iterate
blind. Use an init script rather than editing build files, so nothing tracked changes.

## Typical workflow

1. Baseline: confirm every module builds green *before* changing anything.
2. Fetch mappings, build the dictionary, and check the versions **agree** on every symbol
   the shared sources use. If they disagree, one rename pass cannot serve both modules.
3. `rewrite.py --apply` for classes, then compile with `maxerrs.gradle`.
4. Loop `suggest.py --apply` against the error log until green, compiling **every** module
   each round so a fix for one does not silently break another.
5. Resolve mixin `method=` strings by hand against the jar.
6. Run `../verify_mixins.py` on the built jars. A green build does not mean the mixins
   resolve.

## The thing worth remembering

Everything here exists because the failure mode in this codebase is **silent**. A wrong
rename, an unresolvable mixin target, or a changed sentinel all compile, build and load
cleanly, then behave wrongly at runtime. Verify against the jar; do not trust memory, and
do not trust a green build.
