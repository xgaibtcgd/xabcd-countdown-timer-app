#!/usr/bin/env python3
"""Every tappable region must be both registered and handled.

A control drawn with no hit region is invisible to touch; a region registered with no
case in onRegion does nothing when tapped. Both shipped in the old build -- the pause
button on the adventure screen had no hit test at all, and the entire bottom navigation
bar was decorative.

The Screen classes make that checkable: each declares its regions as R_* constants, and a
constant must appear in layout() (registered) and in onRegion() (handled).
"""
import re, sys, os

DECL = re.compile(r'\b(R_[A-Z0-9_]+)\s*=')
METHOD = re.compile(r'^\s{0,8}(?:@Override\s+)?(?:\w[\w<>\[\], .]*\s+)?(\w+)\s*\([^;{]*\)\s*\{')


def method_bodies(lines):
    """Yields (name, text) for each top-level method in a class body."""
    bodies, depth, current, start = {}, 0, None, 0
    for i, raw in enumerate(lines):
        code = re.sub(r'//.*$', '', raw)
        m = METHOD.match(raw)
        if m and current is None and 'class ' not in raw and 'new ' not in raw:
            current, start = m.group(1), i
        depth += code.count('{') - code.count('}')
        if current is not None and depth <= 1 and i > start:
            bodies.setdefault(current, '')
            bodies[current] += '\n'.join(lines[start:i + 1])
            current = None
    return bodies


def check(path):
    problems = []
    lines = open(path).read().split('\n')
    text = '\n'.join(lines)
    declared = set(DECL.findall(text))
    if not declared:
        return problems

    bodies = method_bodies(lines)
    layout = bodies.get('layout', '')
    handler = bodies.get('onRegion', '')
    press_down = bodies.get('onPressDown', '')

    if not layout:
        problems.append('%s: no layout() found, so nothing can be registered' % path)
        return problems
    if not handler:
        problems.append('%s: no onRegion() found, so nothing can be handled' % path)
        return problems

    for region in sorted(declared):
        registered = region in layout
        handled = region in handler or region in press_down
        if not registered:
            problems.append('%s: %s is declared but never registered in layout(); '
                            'a control drawn without a hit region cannot be tapped'
                            % (os.path.relpath(path), region))
        if not handled:
            problems.append('%s: %s is registered but has no case in onRegion(); '
                            'tapping it would do nothing'
                            % (os.path.relpath(path), region))
    return problems


def main():
    problems = []
    for path in sys.argv[1:]:
        if os.path.exists(path):
            problems.extend(check(path))
    for p in problems:
        print('FAIL: ' + p, file=sys.stderr)
    if problems:
        print('FAIL: %d unreachable or unhandled region(s)' % len(problems), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
