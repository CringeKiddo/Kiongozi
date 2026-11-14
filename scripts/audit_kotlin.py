#!/usr/bin/env python3
"""
Heuristic import audit for the Kiongozi Kotlin sources (no compiler needed).

Checks, per file:
  1. Project classes/objects/enums used from ANOTHER package but not imported.
  2. Any simple name imported somewhere else in the project (Compose, Android, ...) that this file uses
     but does not import.
  3. kotlin.math functions (abs, min, max, floor, roundToInt, sqrt, sin, ...) used without an import.
  4. Compose `by` delegates (remember/mutableStateOf/animate*/collectAsState) without getValue / setValue.
  5. Imports that point at project classes that don't exist.
It is a safety net, not a compiler: it can produce a few false alarms but should never stay silent
about the typical "forgot an import" build error.
Exit code 1 if anything was found.
"""
import re, sys, glob, os
from collections import defaultdict

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "java")
files = sorted(glob.glob(os.path.join(ROOT, "**", "*.kt"), recursive=True))

def strip(src):
    src = re.sub(r'"""[\s\S]*?"""', '""', src)
    src = re.sub(r'/\*[\s\S]*?\*/', '', src)
    src = re.sub(r'//[^\n]*', '', src)
    src = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', src)
    src = re.sub(r"'(?:\\.|[^'\\\n])'", "' '", src)
    return src

info = {}
declared = defaultdict(set)         # simple name -> packages that declare it
all_imports = defaultdict(set)      # simple name -> imported FQNs anywhere
for f in files:
    raw = open(f, encoding="utf-8").read()
    code = strip(raw)
    pkg = re.search(r'^package\s+([\w.]+)', raw, re.M).group(1)
    imps = re.findall(r'^import\s+([\w.]+)(?:\s+as\s+\w+)?\s*$', raw, re.M)
    wild = [i for i in re.findall(r'^import\s+([\w.]+)\.\*\s*$', raw, re.M)]
    decl = set(re.findall(r'\b(?:class|object|interface|typealias|fun|val|var)\s+([A-Z]\w*)', code))
    # extension composables like "fun RowScope.NeonChip(" -> NeonChip
    decl |= set(re.findall(r'\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Z]\w*)\s*\(', code))
    info[f] = dict(pkg=pkg, imps=imps, wild=wild, code=code, decl=decl, raw=raw)
    for d in decl:
        declared[d].add(pkg)
    for i in imps:
        all_imports[i.split(".")[-1]].add(i)

problems = []
def flag(f, msg):
    problems.append(f"{os.path.relpath(f, ROOT)}: {msg}")

KMATH_FUN = ["abs", "min", "max", "floor", "ceil", "sqrt", "sin", "cos", "atan2", "hypot", "ln", "exp", "ln1p"]
KMATH_EXT = ["roundToInt", "roundToLong", "pow"]

for f, d in info.items():
    code, pkg = d["code"], d["pkg"]
    imported_simple = {i.split(".")[-1] for i in d["imps"]}
    imported_fqn = set(d["imps"])
    wild = set(d["wild"])

    # 5. dangling project imports
    for i in d["imps"]:
        if i.startswith("com.kiongozi.app."):
            name = i.split(".")[-1]
            ipkg = ".".join(i.split(".")[:-1])
            parent = i.split(".")[-2]
            if parent[:1].isupper() and parent in declared:
                continue   # nested class import, e.g. BeatmapPipeline.Stage
            if name[0].isupper() and ipkg not in declared.get(name, set()):
                flag(f, f"import {i} does not match any declaration")

    # 1 + 2. used-but-not-imported names
    words = set(re.findall(r'\b([A-Z][A-Za-z0-9_]*)\b', code))
    for w in sorted(words):
        if w in d["decl"] or w in imported_simple:
            continue
        if w.isupper() and len(w) > 1:
            continue   # constants / enum entries
        # project class from another package
        if w in declared:
            if pkg in declared[w]:
                continue
            if any(p in wild for p in declared[w]):
                continue
            if not re.search(r'(?<![\w.])' + re.escape(w) + r'\b', code):
                continue   # only used after a dot (fully qualified or Outer.Inner)
            flag(f, f"uses project class {w} but doesn't import it (declared in {sorted(declared[w])})")
            continue
        # name imported elsewhere in the project (Android / Compose / etc.)
        if w in all_imports:
            fq = all_imports[w]
            if any(".".join(x.split(".")[:-1]) in wild for x in fq):
                continue
            # qualified use like android.graphics.Color.RED is fine
            if re.search(r'(?:\w+\.)+' + re.escape(w) + r'\b', code) and not re.search(r'(?<![\w.])' + re.escape(w) + r'\b', code):
                continue
            # nested use like Paint.Style / Foo.Bar where w is only a member after a dot
            if not re.search(r'(?<![\w.])' + re.escape(w) + r'\b', code):
                continue
            flag(f, f"uses {w} but doesn't import it (imported elsewhere as {sorted(fq)[0]})")

    # 3. kotlin.math
    for fn in KMATH_FUN:
        if re.search(r'(?<![\w.])' + fn + r'\(', code) and f"kotlin.math.{fn}" not in imported_fqn and "kotlin.math" not in wild:
            # skip when it's a locally declared function
            if re.search(r'\bfun\s+' + fn + r'\b', code):
                continue
            flag(f, f"calls {fn}() without import kotlin.math.{fn}")
    for fn in KMATH_EXT:
        if re.search(r'\.' + fn + r'\(', code) and f"kotlin.math.{fn}" not in imported_fqn and "kotlin.math" not in wild:
            flag(f, f"uses .{fn}() without import kotlin.math.{fn}")
    if re.search(r'(?<![\w.])PI\b', code) and "kotlin.math.PI" not in imported_fqn and "kotlin.math" not in wild:
        flag(f, "uses PI without import kotlin.math.PI")

    # 4. Compose delegates
    for m in re.finditer(r'\b(val|var)\s+\w+(?::[^=\n]+)?\s+by\s+(\w+)', code):
        kind, rhs = m.group(1), m.group(2)
        if rhs in ("lazy", "preferencesDataStore", "viewModels", "remember", "mutableStateOf") or rhs.startswith("animate") or rhs in ("collectAsState",):
            if "androidx.compose.runtime.getValue" not in imported_fqn:
                flag(f, f"`{kind} ... by {rhs}` needs import androidx.compose.runtime.getValue")
            if kind == "var" and rhs in ("remember", "mutableStateOf") and "androidx.compose.runtime.setValue" not in imported_fqn:
                flag(f, f"`var ... by {rhs}` needs import androidx.compose.runtime.setValue")

# controller state property delegates (var x by mutableStateOf in classes)
if problems:
    print("AUDIT: %d possible problem(s)\n" % len(problems))
    for p in sorted(set(problems)):
        print(" -", p)
    sys.exit(1)
print("AUDIT OK: %d Kotlin files, no missing/dangling imports found" % len(files))
