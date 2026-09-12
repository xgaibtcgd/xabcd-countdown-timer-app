package com.morningmission.app;

/**
 * A character built from five parts instead of one flat bitmap.
 *
 * <p>Everything else in the app draws a buddy as a single 720x720 PNG transformed as one
 * rectangle. That is what caps the animation: no limb can move, and nothing can lag —
 * ears, tails and wings travel in perfect lockstep with the body, and that trailing half
 * beat is where most of the sense of life comes from.
 *
 * <p>A rig replaces the bitmap, not the transform. The parts are drawn INSIDE the outer
 * transform {@code MorningView.drawSprite} already applies, so the bob, the squash, the
 * contact shadow and the one-shot feast and poke moves all keep working untouched, and an
 * unrigged character takes exactly the path it took before.
 *
 * <h2>The coordinate system</h2>
 *
 * <p>Every number in {@link #layout} is a fraction of the sprite FRAME — the same frame
 * the flat bitmap fills, so a rigged buddy is the same size as the buddy it replaces on
 * every screen. Centres and sizes are fractions of frame width and height; the pivot is a
 * fraction of the part's own box, where {@code (0.5, 0.5)} is its centre.
 *
 * <p>The pivot matters more than it looks. An arm rotated about its middle windmills and
 * pulls its shoulder out of the body; rotated about the shoulder it swings. The rest
 * angles here were tuned against the pivots and are meaningless without them.
 *
 * <p>Degrees turn CLOCKWISE, the way {@code Canvas.rotate} does. The rest pose was
 * authored in a tool whose positive angle went the other way, and transcribing it
 * unflipped put both of the bee's arms across its front like folded arms.
 *
 * <h2>Where the numbers came from</h2>
 *
 * <p>The generator supplied the parts laid out separately on a sheet rather than in
 * place, so there was no position to recover — the rest pose is authored. It was
 * assembled offline, then fitted into the flat sprite's own frame by silhouette overlap
 * (IoU 0.767 for the bee), which is the same fitter that placed the cheer poses.
 */
final class Rig {

    /** Draw order, back to front. The signature part goes behind everything. */
    static final int SIGNATURE = 0, TORSO = 1, ARM_L = 2, ARM_R = 3, HEAD = 4;
    static final int PART_COUNT = 5;

    /** Floats per part in {@link #layout}: cx, cy, w, h, restDegrees, pivotX, pivotY. */
    static final int STRIDE = 7;

    /** Part names in draw order, for the exporter and for failure messages. */
    static final String[] NAMES = { "signature", "torso", "arm_l", "arm_r", "head" };

    /** Field names within a {@link #STRIDE}, in order, for the exporter. */
    static final String[] FIELDS = { "cx", "cy", "w", "h", "rest", "pvx", "pvy" };

    /** One drawable per part, in the same order. */
    final int[] res;

    /** {@link #PART_COUNT} groups of {@link #STRIDE}, all fractions of the frame. */
    final float[] layout;

    /**
     * Frame width as a multiple of frame height.
     *
     * <p>A flat sprite takes this from its own bitmap; a rig has no single bitmap, so it
     * has to say. Every buddy PNG is square, hence 1.
     */
    final float aspect;

    /** How fast the signature part beats, in radians per second. */
    final float signatureBeat;

    /** How far it swings, in degrees. */
    final float signatureSweep;

    private Rig(int[] res, float[] layout, float aspect,
                float signatureBeat, float signatureSweep) {
        this.res = res;
        this.layout = layout;
        this.aspect = aspect;
        this.signatureBeat = signatureBeat;
        this.signatureSweep = signatureSweep;
    }

    /**
     * Where the topmost part starts, as a fraction of the frame.
     *
     * <p>The rigged answer to {@code MorningView.topFraction}, which scans a bitmap. A
     * rig has no single bitmap, but it does know where every part sits, so the same
     * number falls out of the layout. The crown on the Complete screen hangs off it.
     */
    float topFraction() {
        float top = 1f;
        for (int part = 0; part < PART_COUNT; part++) {
            float t = cy(part) - height(part) * 0.5f;
            if (t < top) top = t;
        }
        return top < 0f ? 0f : top;
    }

    float cx(int part)     { return layout[part * STRIDE]; }
    float cy(int part)     { return layout[part * STRIDE + 1]; }
    float width(int part)  { return layout[part * STRIDE + 2]; }
    float height(int part) { return layout[part * STRIDE + 3]; }
    float rest(int part)   { return layout[part * STRIDE + 4]; }
    float pivotX(int part) { return layout[part * STRIDE + 5]; }
    float pivotY(int part) { return layout[part * STRIDE + 6]; }

    // ---------------------------------------------------------------------- the cast

    /**
     * Queen Bee, the first character to be rigged.
     *
     * <p>Chosen because it is the only one of the eight whose signature part appears
     * nowhere else: its wings are absent from both the head and the abdomen, where the
     * pug's tail is baked into its torso and the trike's frill, the dino's sprout and the
     * cloud pup's ears are baked into their heads. Its abdomen is even drawn with an open
     * socket where the head sits. It is also the only character with {@code hover} at
     * full, so it never touches the ground and there is no leg planting to get right —
     * the legs stay part of the abdomen.
     *
     * <p>Its signature move was already {@link Anim#SIG_FLUTTER}, standing in for a wing
     * beat with a whole-sprite wobble. Now the wings actually beat.
     */
    static final Rig BEE = new Rig(
        new int[] {
            R.drawable.buddy_bee_part_wings,
            R.drawable.buddy_bee_part_torso,
            R.drawable.buddy_bee_part_arm_l,
            R.drawable.buddy_bee_part_arm_r,
            R.drawable.buddy_bee_part_head,
        },
        new float[] {
            //  cx       cy       w        h       rest   pivotX pivotY
            0.5450f, 0.4291f, 0.7642f, 0.3538f,    0f,    0.50f, 0.86f,   // wings
            0.5450f, 0.6566f, 0.5417f, 0.5071f,    0f,    0.50f, 0.50f,   // torso
            0.3175f, 0.5483f, 0.2517f, 0.2481f,   78f,    0.74f, 0.26f,   // left arm
            0.7725f, 0.5483f, 0.2481f, 0.2335f,  -78f,    0.26f, 0.26f,   // right arm
            0.5450f, 0.3316f, 0.5326f, 0.4815f,    0f,    0.50f, 0.92f,   // head
        },
        1f, 15f, 17f);
}
