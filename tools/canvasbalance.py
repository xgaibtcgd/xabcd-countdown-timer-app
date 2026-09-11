#!/usr/bin/env python3
"""Every canvas save must have a matching restore, within the same method.

An unbalanced save leaks clip and matrix state into whatever draws next, which shows up
as a screen that is fine until some unrelated element is added and then mysteriously
clipped or offset. It is invisible in review and impossible to spot without running the
app, so it is checked here instead.

Counts save()/saveLayer()/restore()/restoreToCount() per method body. Methods that
deliberately leave a save open for a caller must say so with:
    // canvasbalance: ok - <reason>
"""
import re, sys, os

SAVE = re.compile(r'\b\w+\.save(?:Layer|LayerAlpha)?\s*\(')
RESTORE = re.compile(r'\b\w+\.restore(?:ToCount)?\s*\(')
SIG = re.compile(r'^\s{0,8}(?:@Override\s+)?(?:(?:public|private|protected|static|final|'
                 r'synchronized|abstract)\s+)*(?:[\w\.\<\>\[\]\?, ]+\s+)?(\w+)\s*'
                 r'\([^;]*\)\s*(?:throws [\w, \.]+)?\s*\{')
KEYWORDS = {"if", "for", "while", "switch", "catch", "try", "else", "do",
            "synchronized", "return", "new", "case", "default"}


def check(path):
    problems = []
    lines = open(path).read().split('\n')
    depth = 0
    method = None
    method_depth = 0
    saves = restores = 0
    exempt = False
    start = 0

    for i, raw in enumerate(lines, 1):
        code = re.sub(r'//.*$', '', raw)
        m = SIG.match(raw)
        if m and m.group(1) not in KEYWORDS and 'class ' not in raw and 'new ' not in raw:
            if method is None:
                method, method_depth, start = m.group(1), depth, i
                saves = restores = 0
                exempt = False
        if method is not None:
            saves += len(SAVE.findall(code))
            restores += len(RESTORE.findall(code))
            if 'canvasbalance: ok' in raw:
                exempt = True
        depth += code.count('{') - code.count('}')
        if method is not None and depth <= method_depth:
            if saves != restores and not exempt:
                problems.append('%s:%d: %s() has %d save%s and %d restore%s'
                                % (os.path.relpath(path), start, method,
                                   saves, '' if saves == 1 else 's',
                                   restores, '' if restores == 1 else 's'))
            method = None
    return problems


def main():
    problems = []
    for path in sys.argv[1:]:
        if os.path.exists(path):
            problems.extend(check(path))
    for p in problems:
        print('FAIL: ' + p, file=sys.stderr)
    if problems:
        print('FAIL: %d unbalanced canvas save/restore' % len(problems), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
