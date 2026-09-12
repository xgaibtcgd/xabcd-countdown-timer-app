// The route, mirrored from app/src/main/java/com/morningmission/app/Route.java.
//
// This is the one piece of the preview that has to agree with the app BIT FOR BIT rather
// than merely closely: it is a pseudo-random path, so a generator that is off by one
// draw produces a completely different picture, not a slightly wrong one -- and a
// preview showing a different morning from the app is worse than no preview.
//
// Which is why the app's own random numbers are 32-bit. JavaScript has no 64-bit integer
// arithmetic that survives a multiply, so lowbias32 and the xorshift below reproduce
// exactly here with Math.imul and >>>. tools/routeproof.cjs checks that claim against a
// table the real Java writes into screens.json, so "mirrored" is verified, not asserted.
'use strict';

const ROUTE_MAX_COLS = 12, ROUTE_MAX_ROWS_FLY = 5, ROUTE_MAX_ROWS_WALK = 4;
const ROUTE_MAX_CELLS = ROUTE_MAX_COLS * ROUTE_MAX_ROWS_FLY;
const ROUTE_MOVES = 120, ROUTE_SPARE = 2, ROUTE_MIN_ROW_PITCH = 50;
const ROUTE_WALK_BACK_SCALE = 0.70;
const ROUTE_DX = [1, -1, 0, 0], ROUTE_DY = [0, 0, 1, -1];

function routeSeedFrom(seed) {
  // Java folds the long's two halves together first: (int)(seed ^ (seed >>> 32)), with
  // >>> unsigned over the whole 64 bits. A plain `seed | 0` agrees for every value the
  // app actually produces -- the clock is positive and under 2^32 -- and disagrees for
  // negatives, which tools/routeproof.cjs puts in its table precisely so that "agrees
  // for the values we expect" never gets mistaken for "agrees".
  const bits = BigInt.asUintN(64, BigInt(seed));
  let h = (Number(bits & 0xFFFFFFFFn) ^ Number(bits >> 32n)) | 0;
  h ^= h >>> 16;
  h = Math.imul(h, 0x7FEB352D);
  h ^= h >>> 15;
  h = Math.imul(h, 0x846CA68B);
  h ^= h >>> 16;
  return h === 0 ? 0x9E3779B9 | 0 : h;
}

// Rows first, columns after. aspect 0 asks for as deep a grid as the cap allows, which
// is what a walker's wide shallow band wants -- rows may overlap, width cannot be got back.
function routeRowsFor(cells, aspect, maxRows) {
  let rows = aspect > 0 ? Math.round(Math.sqrt(cells / Math.max(0.05, aspect)))
                        : Math.ceil(cells / 2);
  if (rows < 2) rows = 2;
  return rows > maxRows ? maxRows : rows;
}

function routeColumnsFor(cells, rows) {
  const want = cells + ROUTE_SPARE;
  let cols = Math.ceil(want / rows);
  if (cols < 2) cols = 2;
  while (cols > 2 && (cols - 1) * rows >= want) cols--;
  return cols > ROUTE_MAX_COLS ? ROUTE_MAX_COLS : cols;
}

/** The full Hamiltonian order over a cols x rows grid, as cell indices gridRow*cols+col. */
function routeBuildPath(seed, cols, rows) {
  const n = cols * rows;
  const path = new Array(n);
  const at = new Array(n);
  for (let k = 0, band = 0; band < rows; band++) {
    const gridRow = rows - 1 - band;
    for (let i = 0; i < cols; i++, k++) {
      path[k] = gridRow * cols + ((band & 1) === 0 ? i : cols - 1 - i);
    }
  }
  for (let i = 0; i < n; i++) at[path[i]] = i;

  let rnd = routeSeedFrom(seed);
  const next = bound => {
    let x = rnd;
    x ^= x << 13;
    x ^= x >>> 17;
    x ^= x << 5;
    rnd = x | 0;
    return ((rnd >>> 1) % bound) | 0;
  };
  const reverse = (lo, hi) => {
    while (lo < hi) {
      const swap = path[lo];
      path[lo] = path[hi];
      path[hi] = swap;
      at[path[lo]] = lo;
      at[path[hi]] = hi;
      lo++;
      hi--;
    }
  };

  const moves = ROUTE_MOVES + next(ROUTE_MOVES);
  for (let move = 0; move < moves; move++) {
    const end = path[n - 1];
    const ec = end % cols, er = (end / cols) | 0;
    const spin = next(4);
    for (let d = 0; d < 4; d++) {
      const dir = (spin + d) & 3;
      const nc = ec + ROUTE_DX[dir], nr = er + ROUTE_DY[dir];
      if (nc < 0 || nc >= cols || nr < 0 || nr >= rows) continue;
      const j = at[nr * cols + nc];
      if (j === n - 2) continue;
      reverse(j + 1, n - 1);
      break;
    }
  }
  return path;
}

/** Everything screenAdventure needs: the grid, the path, the region and the depth. */
function makeRoute(seed, drops, region, flies) {
  const cells = drops + 1;
  const aspect = (region[2] - region[0]) / (region[3] - region[1]);
  // A cap on the cap: no row shallower than ROUTE_MIN_ROW_PITCH, whatever the limits say.
  const deepest = Math.floor((region[3] - region[1]) / ROUTE_MIN_ROW_PITCH);
  const maxRows = Math.min(flies ? ROUTE_MAX_ROWS_FLY : ROUTE_MAX_ROWS_WALK,
                           Math.max(2, deepest));
  const rows = routeRowsFor(cells, flies ? aspect : 0, maxRows);
  const cols = routeColumnsFor(cells, rows);
  const path = routeBuildPath(seed, cols, rows);
  const back = flies ? 1 : ROUTE_WALK_BACK_SCALE;
  const cellW = (region[2] - region[0]) / cols;
  const cellH = (region[3] - region[1]) / rows;

  const clamp = i => i < 0 ? 0 : (i >= cells ? cells - 1 : i);
  const colAt = i => path[clamp(i)] % cols;
  // Row 0 is the FRONT -- the bottom of the screen. Grid row 0 is the back.
  const rowAt = i => rows - 1 - ((path[clamp(i)] / cols) | 0);
  // Route.jitter: a fixed nudge off the cell centre so the field is not a lattice.
  // Hashed from the cell and the seed, so the same morning nudges the same way.
  const jitter = (i, axis) => {
    const bits = BigInt.asUintN(64,
        BigInt(seed) ^ (BigInt(path[clamp(i)]) * 0x9E3779B9n) ^ (BigInt(axis) * 0x85EBCA6Bn));
    let h = (Number(bits & 0xFFFFFFFFn) ^ Number(bits >> 32n)) | 0;
    h ^= h >>> 16;
    h = Math.imul(h, 0x7FEB352D);
    h ^= h >>> 15;
    h = Math.imul(h, 0x846CA68B);
    h ^= h >>> 16;
    if (h === 0) h = 0x9E3779B9 | 0;
    return (h >>> 8) / (1 << 24) * 0.26 - 0.13;
  };
  const x = i => region[0] + (colAt(i) + 0.5 + jitter(i, 0)) * cellW;
  const y = i => region[3] - (rowAt(i) + 0.5 + jitter(i, 1)) * cellH;
  const scaleAt = i => rows <= 1 ? 1 : 1 - (1 - back) * rowAt(i) / (rows - 1);

  const segment = s => {
    let seg = Math.floor(s);
    if (seg < 0) return 0;
    return seg > cells - 2 ? cells - 2 : seg;
  };
  const lerp = (fn, s) => {
    const seg = segment(s);
    return fn(seg) + (fn(seg + 1) - fn(seg)) * (s - seg);
  };
  const unit = (seg, wantX) => {
    const dx = x(seg + 1) - x(seg), dy = y(seg + 1) - y(seg);
    const len = Math.sqrt(dx * dx + dy * dy);
    if (len < 1e-4) return wantX ? 1 : 0;
    return (wantX ? dx : dy) / len;
  };
  const heading = (s, wantX) => {
    const seg = segment(s), f = s - seg;
    const nxt = seg + 1 < cells - 1 ? seg + 1 : seg;
    const hx = unit(seg, true) + (unit(nxt, true) - unit(seg, true)) * f;
    const hy = unit(seg, false) + (unit(nxt, false) - unit(seg, false)) * f;
    const len = Math.sqrt(hx * hx + hy * hy);
    if (len < 1e-4) return wantX ? 1 : 0;
    return (wantX ? hx : hy) / len;
  };

  return {
    cols, rows, count: cells, cellW, cellH, path,
    colAt, rowAt, x, y, scaleAt,
    travelX: s => lerp(x, s),
    travelY: s => lerp(y, s),
    travelScale: s => lerp(scaleAt, s),
    headingX: s => heading(s, true),
    headingY: s => heading(s, false),
  };
}

/** Route.regionFor. Hung off advTrail and advScene, so no Layout band moved for it. */
function routeRegion(L, flies, designWidth, horizonFraction) {
  const scene = L.advScene, play = L.play;
  const h = scene[3] - scene[1];
  const bottom = (L.advTrail[1] + L.advTrail[3]) * 0.5;
  let top;
  if (flies) {
    top = scene[1] + h * 0.20;
  } else {
    // A walker's band stops at its own scene's horizon: a fixed share of the scene left
    // the character itself standing in the sky above the treeline.
    const horizon = play[1] + (play[3] - play[1]) * horizonFraction;
    top = Math.max(bottom - h * 0.34, Math.min(horizon, bottom - h * 0.26));
  }
  const inset = designWidth * 0.13;
  return [scene[0] + inset, top, scene[2] - inset, bottom];
}

if (typeof module !== 'undefined') {
  module.exports = { routeSeedFrom, routeRowsFor, routeColumnsFor, routeBuildPath,
                    makeRoute, routeRegion };
}
