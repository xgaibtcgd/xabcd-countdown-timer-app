package com.morningmission.app;

import android.graphics.RectF;

/**
 * This morning's way to the treasure chest: a winding path through a grid of cells.
 *
 * <p>The collectibles used to lie on a single line along the bottom of the scene, six on
 * screen at a time with the rest scrolled in. That left the sky above them empty and it
 * made every morning the same walk. Here the same drops are spread over a grid, the
 * buddy threads between them, and the chest lands somewhere new each time.
 *
 * <h2>The shape of it</h2>
 *
 * <p>There are {@code total + 1} waypoints. Waypoint 0 is where the buddy starts;
 * waypoints {@code 1 .. total-1} carry the drops; waypoint {@code total} carries the
 * chest. Consecutive waypoints are grid neighbours -- never diagonal, never two apart --
 * and no cell is entered twice.
 *
 * <p>Those two facts together are why <b>the path cannot cross itself</b>. Two segments
 * can only lie on top of each other if some cell is visited twice, and no cell is. It is
 * a property of how the path is built rather than something checked afterwards and
 * retried, so there is no failure case to handle and no worst case to bound.
 *
 * <p>They also keep the contract the screen already had: every step is exactly one cell,
 * so parametrising by segment index means the buddy's mouth is on waypoint {@code i} at
 * {@code progress == i / total}, which is the instant {@link Engine} counts drop
 * {@code i} collected.
 *
 * <h2>Backbite</h2>
 *
 * <p>Sampling a random Hamiltonian path: start from a serpentine, which is already one,
 * then repeatedly take an endpoint, find a grid neighbour of it that sits elsewhere on
 * the path, and reverse the run between them. Every move produces another Hamiltonian
 * path, so the generator cannot fail -- it only gets more shuffled. Measured over 2000
 * seeds at each grid size the app produces: no failures, a few hundred microseconds a
 * build, and the chest landing all over the grid.
 *
 * <p>(A self-avoiding walk with Warnsdorff's rule was the obvious alternative and is
 * worse on both counts: it painted itself into a corner on 12% of seeds and took 10
 * milliseconds.)
 *
 * <p>The grid usually holds a cell or two more than the morning needs. That is
 * deliberate: a prefix of a path that does not cross itself does not cross itself
 * either, so the grid can be shaped to fit the region rather than forced to factorise
 * {@code total + 1} -- which for a prime would mean a single straight row.
 *
 * <h2>Off-device</h2>
 *
 * <p>No Android type here but {@link RectF}, and no allocation after construction, so
 * tools/SelfTest.java can hold the whole thing to the wall the way it does {@link Layout}
 * -- and the random numbers are 32-bit by choice so the preview can reproduce a path
 * exactly rather than approximately.
 */
final class Route {

    /** Wide enough for the densest morning; the grid chooser is held to it. */
    static final int MAX_COLS = 12;
    /** Deep enough to read as depth without shrinking the buddy to nothing. */
    static final int MAX_ROWS_FLY = 5, MAX_ROWS_WALK = 4;
    static final int MAX_ROWS = MAX_ROWS_FLY;
    static final int MAX_CELLS = MAX_COLS * MAX_ROWS;

    /**
     * The shallowest a row may be, in design units.
     *
     * <p>A cap on the cap. The row limits above are what the composition wants; this is
     * what the space allows. On the shortest scene the app will lay out -- a wide, low
     * screen with deep insets, where {@code advScene} bottoms out at its 520 minimum --
     * a walker's band is 156 units deep, and four rows of 39 give treats fifty units
     * across. Three rows of 52 give treats half again that size on the same band, because
     * the columns come down from twelve to nine and width is what a drop is short of.
     */
    private static final float MIN_ROW_PITCH = 50f;

    /**
     * Backbite moves per build: this many plus up to this many more, drawn from the seed.
     *
     * <p>The range matters as much as the count. On the smallest grid a morning can
     * produce -- two by two, where the tail has exactly one legal move -- backbite has no
     * choice at all and simply alternates between the two possible paths, so a fixed
     * count made every short morning identical. Varying it restores the coin flip that
     * grid has and costs the larger ones nothing.
     */
    private static final int MOVES = 120;

    /** How much smaller the back row is drawn than the front, for a character that walks. */
    private static final float WALK_BACK_SCALE = 0.70f;

    /**
     * How far a buddy's ink reaches sideways from its own centre, as a fraction of its
     * height.
     *
     * <p>The sprites are square frames with a lot of transparent margin, so treating the
     * frame as the character caps the buddy far harder than it needs to be. Measured
     * across all eight: the cloud pup is the widest at 0.431 and the burger the narrowest
     * at 0.329. The widest is the one that has to fit.
     */
    static final float SPRITE_REACH = 0.44f;

    /**
     * How far off its cell centre a waypoint may be nudged, as a fraction of the cell.
     *
     * <p>Package-visible because the poke region has to allow for it: that bound is
     * derived tightly from where the buddy can stand, and a waypoint that is no longer
     * the cell centre moves where that is.
     */
    static final float JITTER = 0.13f;

    private static final int[] DX = { 1, -1, 0, 0 };
    private static final int[] DY = { 0, 0, 1, -1 };

    /** Where the drops go, in screen units. The buddy hangs below it; see the screen. */
    final RectF region = new RectF();

    private int cols, rows, count;
    private float back = 1f;

    /** The whole Hamiltonian order, {@code cols * rows} long. Only the first {@link #count}. */
    private final int[] path = new int[MAX_CELLS];
    /** Where each cell sits in {@link #path}, so a neighbour's path index is one lookup. */
    private final int[] at = new int[MAX_CELLS];

    private int rnd;
    private long builtSeed = Long.MIN_VALUE;
    private int builtCols = -1, builtRows = -1;

    // ------------------------------------------------------------------ this morning

    /**
     * Point the route at this screen, this morning and this character.
     *
     * <p>Safe to call every frame: the region and the grid are a few divisions, and the
     * path is only rebuilt when the seed or the grid actually changes -- which is once a
     * morning, on the same frame as the scene rebuild.
     *
     * @param flies from {@code temperament.hover > 0}. A bee, a cloud pup and a shark
     *              own the whole scene and are all the same size wherever they are; the
     *              five that walk get a band near the ground, with the rows behind drawn
     *              smaller so the field reads as receding rather than as a wall.
     */
    void layoutFor(Layout layout, Engine engine, int buddyIndex, boolean flies) {
        regionFor(layout, buddyIndex, flies, region);
        int cells = engine.collectibleCount() + 1;
        int deepest = (int) (region.height() / MIN_ROW_PITCH);
        int maxRows = Math.min(flies ? MAX_ROWS_FLY : MAX_ROWS_WALK, Math.max(2, deepest));
        int r = rowsFor(cells, flies ? region.width() / region.height() : 0f, maxRows);
        int c = columnsFor(cells, r);

        cols = c;
        rows = r;
        count = cells;
        back = flies ? 1f : WALK_BACK_SCALE;

        long seed = engine.routeSeed();
        if (seed != builtSeed || c != builtCols || r != builtRows) {
            buildPath(seed, c, r);
            builtSeed = seed;
            builtCols = c;
            builtRows = r;
        }
    }

    /**
     * The band the buddy's feet move through. The drops float above it, at mouth height.
     *
     * <p>Hung off {@code advTrail} and {@code advScene} rather than off a new band in
     * {@link Layout}, so the layout solver -- which tools/SelfTest.java asserts to death
     * at every screen size -- does not move at all for this change. Its bottom is the old
     * ground line, so the front row stands where the single lane used to.
     *
     * <p>A flyer's band starts a sixth of the way down the sky rather than at the top of
     * it: the drops are lifted to mouth height off this band, and at the top of a tall
     * scene that lift was carrying them off the edge.
     *
     * <p>A walker's stops at its own scene's HORIZON, which is why this needs to know
     * which character it is drawing for. A fixed share of the scene was tried first and
     * rendered: half a morning's treats hung in the sky over the trees, and worse, so did
     * the pug, standing on a back row well above the ground it was supposed to be walking
     * on. A back row at seven tenths scale reads as distance only while it is still ON
     * the ground.
     *
     * <p>The clamps stop a high horizon from squeezing the band flat -- the volcano's is
     * 0.64 of the play area, which unclamped leaves a fifth of a scene to fit four rows
     * into. Inside those the band is shallow by design, and it is the GRID that adapts to
     * it: see {@link #columnsFor}, and the deliberately generous vertical allowance in
     * the drop size, which lets rows overlap the way a receding field of anything does.
     */
    static void regionFor(Layout layout, int buddyIndex, boolean flies, RectF out) {
        RectF scene = layout.advScene;
        float bottom = layout.advTrail.centerY();
        float top;
        if (flies) {
            top = scene.top + scene.height() * 0.20f;
        } else {
            RectF play = layout.play;
            float horizon = play.top + play.height() * Scene.HORIZON[buddyIndex];
            float shallowest = bottom - scene.height() * 0.26f;
            float deepest = bottom - scene.height() * 0.34f;
            top = Math.max(deepest, Math.min(horizon, shallowest));
        }
        float inset = Layout.W * 0.13f;
        out.set(scene.left + inset, top, scene.right - inset, bottom);
    }

    /**
     * Cells the grid keeps over and above the waypoints the morning needs.
     *
     * <p>Slack, so the morning walks a PREFIX of the path rather than all of it. That
     * keeps the chest off the free endpoint -- it lands on an interior cell, so the
     * journey does not always finish in a corner -- and it keeps the shortest morning off
     * a two-by-two grid, which holds exactly two paths from a fixed start.
     *
     * <p>What it does NOT do is widen where the chest lands, which is what this comment
     * used to claim. Measured over 2000 seeds at every size, no spare cells at all gives
     * the same spread (12 of 24 against 14 of 28 on an hour's walk, 8 of 16 either way at
     * half an hour), and four gives barely more while shrinking every drop. Two is a
     * shape choice, not a randomness one; there is nothing here to crank up.
     */
    private static final int SPARE = 2;

    /**
     * How deep the grid is: rows first, columns after.
     *
     * <p>Rows are chosen independently rather than falling out of the column count,
     * because the two ways of deriving them disagree -- a chooser that picks columns for
     * a three-row grid and then recomputes {@code ceil(cells / cols)} gets two, loses its
     * spare cells with them, and pins the chest to one corner.
     *
     * <p>An {@code aspect} of zero asks for as DEEP a grid as the cap allows. That is
     * what a walker's band wants: it is wide and shallow, and square cells there mean
     * nine columns of three, at which width nothing readable fits. Rows may overlap each
     * other freely -- a field receding into the distance is supposed to -- so depth costs
     * nothing, while width is the one thing a drop cannot get back.
     *
     * <p>Two rows minimum: a single row is the old straight lane, and backbite cannot
     * move on one.
     */
    static int rowsFor(int cells, float aspect, int maxRows) {
        int rows = aspect > 0f
                 ? Math.round((float) Math.sqrt(cells / Math.max(0.05f, aspect)))
                 : (cells + 1) / 2;
        if (rows < 2) rows = 2;
        return rows > maxRows ? maxRows : rows;
    }

    /**
     * How wide the grid is, given its depth.
     *
     * <p>Enough for {@link #SPARE} cells over what the morning needs, and no more than
     * that: every wasted column is width taken off every drop, and every missing spare
     * cell is somewhere the chest can no longer land.
     */
    static int columnsFor(int cells, int rows) {
        int want = cells + SPARE;
        int cols = (want + rows - 1) / rows;
        if (cols < 2) cols = 2;
        while (cols > 2 && (cols - 1) * rows >= want) cols--;
        return cols > MAX_COLS ? MAX_COLS : cols;
    }

    // ------------------------------------------------------------------- where things are

    int count()  { return count; }
    int cols()   { return cols; }
    int rows()   { return rows; }

    float cellW() { return region.width() / cols; }
    float cellH() { return region.height() / rows; }

    /** Column of waypoint {@code i}, 0 at the left. */
    int colAt(int i) { return path[clampIndex(i)] % cols; }
    /** Row of waypoint {@code i}, 0 at the FRONT -- the bottom of the screen. */
    int rowAt(int i) { return rows - 1 - path[clampIndex(i)] / cols; }

    float x(int i) { return region.left + (colAt(i) + 0.5f + jitter(i, 0)) * cellW(); }
    float y(int i) { return region.bottom - (rowAt(i) + 0.5f + jitter(i, 1)) * cellH(); }

    /**
     * A fixed nudge off the cell centre, so the field does not read as a lattice.
     *
     * <p>Two dozen treats on a grid look like a grid, however windy the path between them
     * is -- the eye finds the columns first and the route second. A nudge of an eighth of
     * a cell is enough to break that up and small enough that no two ever collide.
     *
     * <p>Hashed from the cell and the morning's seed rather than stored, so it costs
     * nothing, never has to be rebuilt, and lands in the same place every frame. It goes
     * inside {@link #x} and {@link #y}, which means the buddy, its mouth, the dotted
     * trail and the chest all follow it without knowing it exists, and the contract that
     * the mouth is on drop {@code i} at {@code i / total} holds exactly as before.
     */
    private float jitter(int i, int axis) {
        int h = seedFrom(builtSeed ^ (path[clampIndex(i)] * 0x9E3779B9L) ^ (axis * 0x85EBCA6BL));
        return (h >>> 8) / (float) (1 << 24) * (JITTER * 2f) - JITTER;
    }

    /** How big a thing on waypoint {@code i} is drawn, 1 at the front. */
    float scaleAt(int i) { return depthOfRow(rowAt(i)); }

    private float depthOfRow(int row) {
        return rows <= 1 ? 1f : 1f - (1f - back) * row / (rows - 1);
    }

    private int clampIndex(int i) {
        return i < 0 ? 0 : (i >= count ? count - 1 : i);
    }

    // ------------------------------------------------------------------- travelling it

    /**
     * The buddy's mouth, {@code s} segments along the route.
     *
     * <p>{@code s} is {@code progress() * total}, so it is whole exactly when a drop is
     * collected and the mouth is then on that drop with nothing left over.
     */
    float travelX(float s) {
        int seg = segment(s);
        return x(seg) + (x(seg + 1) - x(seg)) * (s - seg);
    }

    float travelY(float s) {
        int seg = segment(s);
        return y(seg) + (y(seg + 1) - y(seg)) * (s - seg);
    }

    float travelScale(float s) {
        int seg = segment(s);
        return scaleAt(seg) + (scaleAt(seg + 1) - scaleAt(seg)) * (s - seg);
    }

    /**
     * Which way the buddy is heading, as a unit vector, x component.
     *
     * <p>Turned gradually across the segment rather than snapped at the corner. The
     * buddy is drawn a mouth-length BEHIND its mouth along this direction, so a direction
     * that jumped at each waypoint would jerk the whole character sideways by twice that
     * at every turn. Blending toward the NEXT segment's heading is what makes it
     * continuous: at the end of one segment it already points the way the next one goes.
     */
    float headingX(float s) { return heading(s, true); }
    float headingY(float s) { return heading(s, false); }

    private float heading(float s, boolean wantX) {
        int seg = segment(s);
        float f = s - seg;
        int nxt = seg + 1 < count - 1 ? seg + 1 : seg;
        float ax = unit(seg, true),  ay = unit(seg, false);
        float bx = unit(nxt, true),  by = unit(nxt, false);
        float hx = ax + (bx - ax) * f;
        float hy = ay + (by - ay) * f;
        float len = (float) Math.sqrt(hx * hx + hy * hy);
        if (len < 1e-4f) return wantX ? 1f : 0f;
        return (wantX ? hx : hy) / len;
    }

    /** Unit direction of segment {@code seg}, from waypoint seg to seg+1. */
    private float unit(int seg, boolean wantX) {
        float dx = x(seg + 1) - x(seg);
        float dy = y(seg + 1) - y(seg);
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) return wantX ? 1f : 0f;
        return (wantX ? dx : dy) / len;
    }

    private int segment(float s) {
        int seg = (int) Math.floor(s);
        if (seg < 0) return 0;
        return seg > count - 2 ? count - 2 : seg;
    }

    // ------------------------------------------------------------------- building it

    /**
     * A serpentine laid from the front-left corner, then {@link #MOVES} backbites of its
     * far end only.
     *
     * <p>Pinning the near end is the one thing that is not random, and it is worth a
     * paragraph because the obvious alternative does not work. Backbiting both ends and
     * then turning the result so the better endpoint leads sounds equivalent, and is not:
     * the grid usually holds a cell or two more than the morning needs, so the chest is a
     * few steps short of the far endpoint rather than on it, and turning the path moves
     * the chest to the other end of the grid entirely. Backbiting only the tail leaves
     * {@code path[0]} exactly where the serpentine put it, whatever the prefix length.
     *
     * <p>Which is what a child should see: the journey sets off from the near corner and
     * ends somewhere new. The chest is the surprise, the starting line is not.
     *
     * <p>Fixing one endpoint does not cost the shuffle anything that matters -- backbite
     * still reaches all over the grid from the free end, which is where the chest is.
     * tools/SelfTest.java counts the distinct cells it lands on across thirty-two
     * mornings rather than taking that on trust.
     */
    private void buildPath(long seed, int cols, int rows) {
        int n = cols * rows;
        // Laid from the FRONT row up, so path[0] is the front-left cell. Grid row 0 is
        // the back -- see rowAt -- hence counting down from rows-1 here.
        for (int k = 0, band = 0; band < rows; band++) {
            int gridRow = rows - 1 - band;
            for (int i = 0; i < cols; i++, k++) {
                path[k] = gridRow * cols + ((band & 1) == 0 ? i : cols - 1 - i);
            }
        }
        for (int i = 0; i < n; i++) at[path[i]] = i;

        rnd = seedFrom(seed);
        int moves = MOVES + next(MOVES);
        for (int move = 0; move < moves; move++) {
            int end = path[n - 1];
            int ec = end % cols, er = end / cols;
            int spin = next(4);
            for (int d = 0; d < 4; d++) {
                int dir = (spin + d) & 3;
                int nc = ec + DX[dir], nr = er + DY[dir];
                if (nc < 0 || nc >= cols || nr < 0 || nr >= rows) continue;
                int j = at[nr * cols + nc];
                // Its own path-neighbour is a no-op: that edge is already there.
                if (j == n - 2) continue;
                // Add the edge from the tail to j, drop the one on the far side of j.
                reverse(j + 1, n - 1);
                break;
            }
        }
    }

    private void reverse(int lo, int hi) {
        while (lo < hi) {
            int swap = path[lo];
            path[lo] = path[hi];
            path[hi] = swap;
            at[path[lo]] = lo;
            at[path[hi]] = hi;
            lo++;
            hi--;
        }
    }

    /**
     * 32 bits on purpose.
     *
     * <p>tools/preview/screens-ui.js has to walk the same path this does, and JavaScript
     * has no 64-bit integer arithmetic that survives a multiply. The lowbias32 finaliser
     * and the xorshift below are both exactly reproducible there with {@code Math.imul}
     * and {@code >>>}, so the preview shows the morning the app would show rather than a
     * different one that merely looks similar.
     */
    static int seedFrom(long seed) {
        int h = (int) (seed ^ (seed >>> 32));
        h ^= h >>> 16;
        h *= 0x7FEB352D;
        h ^= h >>> 15;
        h *= 0x846CA68B;
        h ^= h >>> 16;
        return h == 0 ? 0x9E3779B9 : h;
    }

    /**
     * The raw path for one grid and one seed, as a JSON array.
     *
     * <p>Only tools/ExportScreens.java calls this, to write a table that
     * tools/routeproof.cjs holds the preview's JavaScript copy of this generator
     * against. It exists here, on the real class, rather than as a reimplementation in
     * the exporter, because a proof generated by a second copy proves nothing.
     */
    static String proofPath(long seed, int cols, int rows) {
        Route r = new Route();
        r.buildPath(seed, cols, rows);
        StringBuilder sb = new StringBuilder("[");           // allocgate: ok - not a frame path
        for (int i = 0; i < cols * rows; i++) {
            if (i > 0) sb.append(",");
            sb.append(r.path[i]);
        }
        return sb.append("]").toString();
    }

    private int next(int bound) {
        int x = rnd;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        rnd = x;
        return (x >>> 1) % bound;
    }
}
