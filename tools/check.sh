#!/usr/bin/env bash
# Offline verification for Morning Mission.
#
# The whole app is drawn with android.graphics, so the cheapest way to catch a
# wrong Canvas/Paint overload, a typo or a bad signature is to actually compile
# it. That normally needs the Android SDK, which isn't installable everywhere
# (dl.google.com is blocked in the sandbox this was written in).
#
# Instead we compile against org.robolectric:android-all -- a genuine Android
# framework jar published to Maven Central -- plus a generated R stub. That is
# full type-checking of every framework call, with no SDK and no emulator.
#
# tools/checksounds.py measures the generated audio, because nothing in CI has
# ears and "they all sound the same" is not something a compiler notices.
#
# Then tools/SelfTest.java exercises the pure-logic classes (Layout, HitMap,
# BuddyTheme, Engine, icon geometry) under a plain JVM. RectF and Color work
# fine off-device; Path is native-backed and throws UnsatisfiedLinkError, which
# is why icon geometry is authored as float[] command arrays, not Path
# constants. Finally tools/allocgate.py fails the build on per-frame
# allocations.
#
# This does NOT replace building the APK in Android Studio -- it cannot render
# a pixel or catch a resource-linking error.
#
# Usage: tools/check.sh [--quiet]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT/tools/.cache"
OUT="$CACHE/classes"
RSTUB="$CACHE/rstub"
JAR="$CACHE/android-all.jar"
VERSION="16-robolectric-13921718"
URL="https://repo1.maven.org/maven2/org/robolectric/android-all/${VERSION}/android-all-${VERSION}.jar"
PKG="$ROOT/app/src/main/java/com/morningmission/app"

QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1
say() { [ "$QUIET" = 1 ] || echo "$@"; }

mkdir -p "$CACHE"

# ---------------------------------------------------------------- framework jar
if [ ! -s "$JAR" ]; then
  say "==> downloading android-all $VERSION (~190MB, cached in tools/.cache)"
  curl -fsSL -o "$JAR.part" "$URL"
  mv "$JAR.part" "$JAR"
fi

# ------------------------------------------------------------------- R stub
rm -rf "$RSTUB"
python3 "$ROOT/tools/genrstub.py" "$ROOT/app/src/main/res" \
        "$RSTUB/com/morningmission/app/R.java" >/dev/null
say "==> R stub regenerated from app/src/main/res"

# ------------------------------------------------------------------- compile
rm -rf "$OUT"; mkdir -p "$OUT"
find "$ROOT/app/src/main/java" "$RSTUB" -name '*.java' | sort > "$CACHE/sources.txt"
# --release 17 matches compileOptions in app/build.gradle.kts.
# -implicit:none keeps the framework jar's shadowed java.* classes out.
if ! javac --release 17 -classpath "$JAR" -implicit:none -nowarn \
           -d "$OUT" "@$CACHE/sources.txt" > "$CACHE/javac.log" 2>&1; then
  grep -v '^Picked up' "$CACHE/javac.log" >&2
  echo "FAIL: compilation errors" >&2
  exit 1
fi
grep -v -e '^Picked up' -e '^Note:' "$CACHE/javac.log" >&2 || true
say "==> compiled $(wc -l < "$CACHE/sources.txt" | tr -d ' ') source files clean"

# ------------------------------------------------------------------ self-test
if [ -f "$ROOT/tools/SelfTest.java" ]; then
  if ! javac --release 17 -classpath "$JAR:$OUT" -implicit:none -nowarn \
             -d "$OUT" "$ROOT/tools/SelfTest.java" > "$CACHE/selftest.log" 2>&1; then
    grep -v '^Picked up' "$CACHE/selftest.log" >&2
    echo "FAIL: SelfTest does not compile" >&2
    exit 1
  fi
  java -cp "$JAR:$OUT" com.morningmission.app.SelfTest 2>&1 | grep -v '^Picked up'
fi

# ------------------------------------------------ region reachability gate
python3 "$ROOT/tools/regioncheck.py" "$PKG"/Screen*.java

# --------------------------------------------------- canvas save/restore gate
python3 "$ROOT/tools/canvasbalance.py" "$PKG"/*.java

# ------------------------------------------------------ sound distinctness gate
python3 "$ROOT/tools/checksounds.py" "$ROOT/app/src/main/res/raw"

# ------------------------------------------------- per-frame allocation gate
python3 "$ROOT/tools/allocgate.py" \
  "$PKG"/Screen*.java "$PKG"/Scene.java "$PKG"/Icons.java "$PKG"/Glyphs.java \
  "$PKG"/Celebration.java \
  "$PKG"/Clay.java "$PKG"/Particles.java "$PKG"/Anim.java "$PKG"/MorningView.java \
  "$PKG"/Theme.java

say "==> OK"
