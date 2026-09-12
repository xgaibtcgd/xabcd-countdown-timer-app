package com.morningmission.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Draws the activity icons, collectibles, goals and UI glyphs.
 *
 * <p>Geometry comes from {@link Art} as data and is compiled to Paths once here. Nothing
 * in this class allocates after class initialisation: drawing scales the canvas rather
 * than transforming paths.
 *
 * <p><b>On colour.</b> Everyday objects keep their natural colours -- the shirt stays
 * blue, the toothbrush stays red -- and the chosen buddy comes through in the tinted chip
 * behind them, the way the mockup's task rows work. Tinting the objects themselves was
 * tried first and produced a blue sun. Collectibles and goals, which belong to the
 * adventure rather than the routine, do carry the buddy's own colours.
 */
final class Icons {

    private Icons() {}

    private static final Path[][] ACTIVITY = new Path[Art.ACT_COUNT][];
    /** [buddy][bites taken 0..2][part] -- the bitten variants are compiled once here. */
    private static final Path[][][] COLLECTIBLE = new Path[BuddyTheme.COUNT][][];
    private static final Path[] GLYPH = new Path[Art.GLYPH_COUNT];
    /** Where each goal's lid hinges, in unit coordinates. */

    private static final RectF BOUNDS = new RectF();
    private static final RectF SCRATCH = new RectF();

    static {
        for (int i = 0; i < Art.ACT_COUNT; i++) {
            ACTIVITY[i] = Clay.compileAll(Art.ACTIVITY_SHAPES[i]);
        }
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            COLLECTIBLE[i] = biteVariants(Art.COLLECTIBLE_SHAPES[i]);
        }
        for (int i = 0; i < Art.GLYPH_COUNT; i++) {
            GLYPH[i] = Clay.compile(Art.GLYPHS[i]);
        }
    }

    /**
     * The whole item plus one part-eaten variant per bite, compiled once.
     *
     * <p>A real boolean difference, not a counter-wound subpath: a bite circle straddles
     * the item's outline, and the piece of it lying outside would otherwise fill rather
     * than cut. {@code Path.op} does the geometry properly and the result is an ordinary
     * path, so it antialiases like everything else, Clay's rim light traces the new edge
     * as depth, and there is nothing to pay for it per frame.
     */
    private static Path[][] biteVariants(float[][] shapes) {
        Path[][] out = new Path[Art.BITE_COUNT][];        // allocgate: ok - class initialisation
        float[] bounds = new float[4];                    // allocgate: ok - class initialisation
        float[] circle = new float[3];                    // allocgate: ok - class initialisation
        Art.shapeBounds(shapes, bounds);
        for (int bites = 0; bites < Art.BITE_COUNT; bites++) {
            out[bites] = Clay.compileAll(shapes);
            if (bites == 0) continue;
            // One circle, not the union of every bite so far: each cut's region contains
            // the one before it, and keeping the removed area a single convex piece is
            // what stops the item breaking into crumbs. See Art.biteCircle.
            Path cut = new Path();                        // allocgate: ok - class initialisation
            Art.biteCircle(bounds, bites - 1, circle);
            cut.addCircle(circle[0], circle[1], circle[2], Path.Direction.CW);
            for (Path part : out[bites]) {
                part.op(cut, Path.Op.DIFFERENCE);
            }
        }
        return out;
    }

    /** Resolves a part colour: a palette role below 16, otherwise a literal ARGB value. */
    private static int resolve(int color, BuddyTheme theme) {
        if (!Art.isRole(color)) return color;
        switch (color) {
            case Art.ROLE_PRIMARY: return theme.primary;
            case Art.ROLE_ACCENT:  return theme.accent;
            case Art.ROLE_ACCENT2: return theme.accent2;
            case Art.ROLE_LIGHT:   return theme.light;
            case Art.ROLE_DARK:    return theme.dark;
            case Art.ROLE_INK:     return theme.ink;
            case Art.ROLE_BODY:    return theme.body;
            case Art.ROLE_CHEEK:   return BuddyTheme.CHEEK;
            default:               return theme.primary;
        }
    }

    private static void parts(Canvas c, Path[] paths, int[] colors, int[] flags, BuddyTheme theme) {
        for (int i = 0; i < paths.length; i++) {
            Clay.part(c, paths[i], resolve(colors[i], theme), flags[i]);
        }
    }

    // ------------------------------------------------------------- activity icons

    /**
     * A task icon on a buddy-tinted round chip, as the mockup's routine rows show.
     *
     * @param size the diameter of the chip
     */
    static void activityChip(Canvas c, int kind, BuddyTheme theme,
                             float cx, float cy, float size, boolean done) {
        int chipColor = done ? 0xFFE2F6E6 : theme.light;
        Clay.contactShadow(c, cx, cy + size * 0.42f, size * 0.40f, size * 0.12f, 0.8f);

        Paint fill = Theme.FILL;
        fill.setShader(null);
        Clay.begin(c, cx, cy, size);
        SCRATCH.set(4f, 4f, 96f, 96f);
        fill.setColor(chipColor);
        c.drawOval(SCRATCH, fill);
        Clay.end(c);

        activity(c, kind, theme, cx, cy, size * 0.70f);
    }

    /** The icon alone, with no chip behind it. */
    static void activity(Canvas c, int kind, BuddyTheme theme, float cx, float cy, float size) {
        int k = (kind < 0 || kind >= Art.ACT_COUNT) ? Art.ACT_DRESS : kind;
        Clay.begin(c, cx, cy, size);
        parts(c, ACTIVITY[k], Art.ACTIVITY_COLORS[k], Art.ACTIVITY_FLAGS[k], theme);
        Clay.end(c);
    }

    // ------------------------------------------------------------------ collectibles

    /**
     * One item on the trail.
     *
     * <p>An item not yet reached is drawn desaturated rather than faded. The old build
     * dropped the alpha to 70, which made pending items nearly invisible against the
     * dark seabed of the shark theme; the mockup shows them as solid grey fish.
     *
     * @param pop 0 at rest, up to 1 for the moment an item is picked up
     */
    static void collectible(Canvas c, int buddy, float cx, float cy, float size,
                            boolean collected, float pop) {
        collectible(c, buddy, cx, cy, size, collected, pop, collected, 0);
    }

    /**
     * The same, with the check badge under separate control.
     *
     * <p>An item sitting on the trail waiting to be reached is drawn in full colour --
     * it is the thing the buddy is heading for, so it should look worth heading for --
     * but it has plainly not been collected yet, so it must not wear the badge. Only the
     * tally does.
     */
    static void collectible(Canvas c, int buddy, float cx, float cy, float size,
                            boolean collected, float pop, boolean badged) {
        collectible(c, buddy, cx, cy, size, collected, pop, badged, 0);
    }

    /**
     * The same, part-eaten.
     *
     * @param bites 0 for whole, 1 or 2 for a bite or two taken out of it; at
     *              {@link Art#BITE_COUNT} there is nothing left to draw
     */
    static void collectible(Canvas c, int buddy, float cx, float cy, float size,
                            boolean collected, float pop, boolean badged, int bites) {
        if (size <= 0.5f || bites >= Art.BITE_COUNT) return;
        BuddyTheme theme = BuddyTheme.of(buddy);
        int index = theme.index;
        Path[] paths = COLLECTIBLE[index][bites < 0 ? 0 : bites];
        int[] colors = Art.COLLECTIBLE_COLORS[index];
        int[] flags = Art.COLLECTIBLE_FLAGS[index];

        float scale = 1f + 0.35f * Theme.clamp(pop, 0f, 1f);
        float drawn = size * scale;

        Clay.begin(c, cx, cy, drawn);
        for (int i = 0; i < paths.length; i++) {
            int colour = resolve(colors[i], theme);
            if (!collected) colour = Theme.desaturate(colour, 0.78f);
            Clay.part(c, paths[i], colour, collected ? flags[i] : (flags[i] & ~Clay.SPEC));
        }
        Clay.end(c);

        if (badged) {
            float badge = size * 0.34f;
            float bx = cx + size * 0.38f, by = cy - size * 0.38f;
            Paint fill = Theme.FILL;
            fill.setShader(null);
            fill.setColor(Theme.SUCCESS);
            c.drawCircle(bx, by, badge, fill);
            glyph(c, Art.GLYPH_CHECK, bx, by, badge * 1.25f, 0xFFFFFFFF);
        }
    }

    // ------------------------------------------------------------------------ goals

    /** A padlock badge over a goal that has not been reached. */
    static void goalLocked(Canvas c, float cx, float cy, float size) {
        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setColor(0xCC50627D);
        c.drawCircle(cx, cy, size * 0.22f, fill);
        glyph(c, Art.GLYPH_LOCK, cx, cy, size * 0.26f, 0xFFFFFFFF);
    }

    // ------------------------------------------------------------------- UI glyphs

    /**
     * A flat, single-colour interface glyph. Deliberately not modelled: interface
     * chrome should read as crisp and flat next to the modelled objects, and these are
     * often drawn small enough that the clay passes would only muddy them.
     */
    static void glyph(Canvas c, int which, float cx, float cy, float size, int color) {
        glyph(c, which, cx, cy, size, color, 0f);
    }

    /** The same, turned. Used by the emote particles, which tumble as they rise. */
    static void glyph(Canvas c, int which, float cx, float cy, float size, int color,
                      float rotationDegrees) {
        if (which < 0 || which >= Art.GLYPH_COUNT) return;
        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        Clay.begin(c, cx, cy, size, rotationDegrees);
        c.drawPath(GLYPH[which], fill);
        Clay.end(c);
    }

    /** A glyph centred in a rectangle, at a fraction of the shorter side. */
    static void glyphIn(Canvas c, int which, RectF box, float fraction, int color) {
        glyph(c, which, box.centerX(), box.centerY(),
              Math.min(box.width(), box.height()) * fraction, color);
    }

    /** A circular chip with a glyph on it: the gear, back, pause and close controls. */
    static void glyphChip(Canvas c, int which, RectF box, int chipColor, int glyphColor,
                          float press) {
        float size = Math.min(box.width(), box.height());
        float cx = box.centerX();
        float cy = box.centerY() + size * 0.06f * Theme.clamp(press, 0f, 1f);
        Clay.contactShadow(c, cx, cy + size * 0.40f, size * 0.38f, size * 0.12f,
                           1f - 0.4f * Theme.clamp(press, 0f, 1f));
        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setColor(chipColor);
        c.drawCircle(cx, cy, size * 0.5f, fill);
        // The same highlight the buttons and bubbles carry, dimmed under a press, so
        // every round surface in the app reads as one material.
        Theme.glossCircle(c, cx, cy, size * 0.5f, 1f - 0.45f * Theme.clamp(press, 0f, 1f));
        glyph(c, which, cx, cy, size * 0.52f, glyphColor);
    }
}
