#!/usr/bin/env python3
"""Fail if a method that runs every frame allocates.

The app redraws at display refresh rate, so an allocation inside a draw path
turns into GC pressure and visible stutter. The original single-file build did
this constantly -- new RectF/Path/LinearGradient and String concatenation in
onDraw -- and it is easy to reintroduce without noticing.

Policy: allocating types may only be constructed in a *setup* context, i.e. a
static initialiser, a field declaration, a constructor, or a method whose name
is in SETUP_METHODS (layout/rebuild/measure/build/init/...). Anything else in a
frame-path file is an error.

Escape hatch: put  // allocgate: ok - <reason>  on the line, or on the line above it
when the statement wraps.
"""
import re, sys, os

ALLOC = re.compile(r'\bnew\s+(Paint|Rect|RectF|Path|Matrix|Region|'
                   r'LinearGradient|RadialGradient|SweepGradient|ComposeShader|'
                   r'BitmapShader|Bitmap|Canvas|StaticLayout|Typeface|'
                   r'String|StringBuilder|ArrayList|HashMap)\s*[\(\[]')
ARRAY = re.compile(r'\bnew\s+(float|int|long|double|boolean|String|Object)\s*\[')
# A method or constructor signature at class-body indentation.
SIG = re.compile(r'^\s{0,8}(?:(?:public|private|protected|static|final|'
                 r'synchronized|abstract|native|strictfp)\s+)*'
                 r'(?:[\w\.\<\>\[\]\?, ]+\s+)?(\w+)\s*\([^;]*\)\s*(?:throws [\w, \.]+)?\s*\{')
# A method whose name starts with one of these is setup, not a frame path.
SETUP_PREFIXES = ("layout", "rebuild", "measure", "build", "init", "prepare", "load",
                  "compile", "cache", "warm", "create", "configure", "make")
SETUP = {"onSizeChanged", "onAttachedToWindow", "reset", "of", "main"}

def is_setup(name):
    return name in SETUP or name.startswith(SETUP_PREFIXES)
# Control-flow keywords also match SIG; they are blocks, not method bodies.
KEYWORDS = {"if", "for", "while", "switch", "catch", "try", "else", "do",
            "synchronized", "return", "new", "case", "default"}

def check(path):
    errs = []
    src = open(path).read().split("\n")
    depth = 0            # brace depth
    stack = []           # (name, depth_at_entry) for open methods
    for i, line in enumerate(src, 1):
        code = re.sub(r'//.*$', '', line)
        code = re.sub(r'"(?:\\.|[^"\\])*"', '""', code)   # blank out string literals
        m = SIG.match(line)
        if (m and m.group(1) not in KEYWORDS
                and "class " not in line and "interface " not in line):
            stack.append((m.group(1), depth))
        exempt = ("allocgate: ok" in line
                  or (i >= 2 and "allocgate: ok" in src[i - 2]))
        if (ALLOC.search(code) or ARRAY.search(code)) and not exempt:
            method = stack[-1][0] if stack else None
            in_setup = method is None or is_setup(method) or method[0].isupper()
            is_field = not stack and depth <= 1
            is_static_init = "static" in line and "{" in line
            if not (in_setup or is_field or is_static_init):
                errs.append((i, method or "<field>", line.strip()[:96]))
        depth += code.count("{") - code.count("}")
        while stack and depth <= stack[-1][1]:
            stack.pop()
    return errs

def main():
    total = 0
    for path in sys.argv[1:]:
        if not os.path.exists(path):
            continue
        for line_no, method, text in check(path):
            total += 1
            print("%s:%d: allocation in per-frame method %s()\n    %s"
                  % (os.path.relpath(path), line_no, method, text), file=sys.stderr)
    if total:
        print("FAIL: %d per-frame allocation(s). Hoist to a field, or annotate "
              "with  // allocgate: ok - <reason>" % total, file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
