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
 * <p>Nor are the two arms a mirror of each other -- their bitmaps differ, 162x160
 * against 160x151 -- so mirroring the placement did not mirror the join, and the right
 * arm hung off the body with a seam showing. What settles it is how much of an arm is
 * tucked behind the head and torso together: the left is 51% covered, the right was 12%,
 * and it is 49% now. Measuring against the abdomen alone says 13% and 0%, which points
 * the wrong way -- on a character this round the shoulder lands on the head.
 *
 * <h2>Where the numbers came from</h2>
 *
 * <p>The generator supplied the parts laid out separately on a sheet rather than in
 * place, so there was no position to recover — the rest pose is authored. It was
 * assembled offline, then fitted into the flat sprite's own frame by silhouette overlap
 * (IoU 0.767 for the bee), which is the same fitter that placed the cheer poses.
 *
 * <p>The other seven are fitted rather than authored: the head is located in the flat
 * sprite by masked cross-correlation, which fixes the scale for every part, and the body
 * is then nudged until the assembly matches the sprite in COLOUR, pixel by pixel.
 * Silhouette overlap was tried first and is actively misleading — an arm folded flat
 * against the belly has the same silhouette as no arm at all, so it rewards hiding every
 * limb inside the torso, and it did, on all eight. The overlap figures quoted per
 * character below are reported, not optimised.
 *
 * <p>Three of the seven needed a step before any of that: see {@link #DINO}.
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

    /**
     * Whether the signature part squashes toward its root as well as turning.
     *
     * <p>True for a wing, which is a pair drawn as one bitmap and cannot flap by
     * rotating -- squashing it toward the back is what stands in for the beat. False
     * for everything else: a tail that shortened and lengthened would read as broken,
     * not as wagging.
     */
    final boolean signatureSquash;

    /**
     * Whether the signature part draws in FRONT of the body instead of behind it.
     *
     * <p>One of the eight needs this. Burger Buddy's signature part is the cheeseburger
     * it is holding, which is not a limb and is not behind anything.
     */
    final boolean signatureInFront;

    private Rig(int[] res, float[] layout, float aspect,
                float signatureBeat, float signatureSweep) {
        this(res, layout, aspect, signatureBeat, signatureSweep, true, false);
    }

    private Rig(int[] res, float[] layout, float aspect,
                float signatureBeat, float signatureSweep,
                boolean signatureSquash, boolean signatureInFront) {
        this.res = res;
        this.layout = layout;
        this.aspect = aspect;
        this.signatureBeat = signatureBeat;
        this.signatureSweep = signatureSweep;
        this.signatureSquash = signatureSquash;
        this.signatureInFront = signatureInFront;
    }

    /** The parts in draw order for THIS rig, which depends on where the signature goes. */
    int partAt(int slot) {
        if (!signatureInFront) return slot;
        return slot == PART_COUNT - 1 ? SIGNATURE : slot + 1;
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
     * <p>Chosen because it was the only one of the eight whose signature part appeared
     * nowhere else: its wings are absent from both the head and the abdomen, where the
     * pug's tail is drawn into its torso and the trike's frill, the dino's sprout and the
     * cloud pup's ears were each drawn into their heads as well as supplied loose. Those
     * three are unpicked now — the sheet's copy is keyed back out of the head bitmap, so
     * the loose part is the only one there is. Its abdomen is even drawn with an open
     * socket where the head sits. It is also the only character with {@code hover} at
     * full, so it never touches the ground and there is no leg planting to get right —
     * the legs stay part of the abdomen.
     *
     * <p>Its signature move was already {@link Anim#SIG_FLUTTER}, standing in for a wing
     * beat with a whole-sprite wobble. Now the wings actually beat.
     */
    static final Rig BEE = new Rig(
        new int[] {
            R.drawable.buddy_bee_part_sig,
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
            0.7000f, 0.5483f, 0.2481f, 0.2335f,  -78f,    0.26f, 0.26f,   // right arm
            0.5450f, 0.3316f, 0.5326f, 0.4815f,    0f,    0.50f, 0.92f,   // head
        },
        1f, 15f, 17f);

    /**
     * Great White Buddy. Its signature part is the tail fin, which sways rather
     * than beats -- a fin driven at a wing's rate reads as a twitch.
     *
     * <p>Placed by the fitter rather than by hand: the head located in the flat sprite
     * by masked cross-correlation, which fixes the scale for every part, then the whole
     * body nudged until the assembly matches the sprite in colour, pixel by pixel.
     * Silhouette overlap 0.789 against the sprite it replaces.
     */
    static final Rig SHARK = new Rig(
        new int[] {
            R.drawable.buddy_shark_part_sig,
            R.drawable.buddy_shark_part_torso,
            R.drawable.buddy_shark_part_arm_l,
            R.drawable.buddy_shark_part_arm_r,
            R.drawable.buddy_shark_part_head,
        },
        new float[] {
            //  cx        cy        w        h       rest    pivotX pivotY
              0.5880f,  0.6291f,  0.4791f,  0.4872f,    -5.2f,  0.26f,  0.44f,   // sig
              0.4750f,  0.5984f,  0.5621f,  0.4765f,     5.2f,  0.50f,  0.50f,   // torso
              0.3821f,  0.6002f,  0.4336f,  0.4399f,    79.8f,  0.74f,  0.24f,   // arm_l
              0.5429f,  0.5513f,  0.3654f,  0.3671f,   -92.0f,  0.35f,  0.32f,   // arm_r
              0.5000f,  0.3743f,  0.6182f,  0.7733f,   -11.3f,  0.48f,  0.65f,   // head
        },
        1f, 4.0f, 9.0f, false, false);

    /**
     * Pug Buddy. The signature part is its curled tail; the "arms" are its front
     * paws, which is why they hang so low on the body.
     *
     * <p>Placed by the fitter rather than by hand: the head located in the flat sprite
     * by masked cross-correlation, which fixes the scale for every part, then the whole
     * body nudged until the assembly matches the sprite in colour, pixel by pixel.
     * Silhouette overlap 0.843 against the sprite it replaces.
     */
    static final Rig PUG = new Rig(
        new int[] {
            R.drawable.buddy_pug_part_sig,
            R.drawable.buddy_pug_part_torso,
            R.drawable.buddy_pug_part_arm_l,
            R.drawable.buddy_pug_part_arm_r,
            R.drawable.buddy_pug_part_head,
        },
        new float[] {
            //  cx        cy        w        h       rest    pivotX pivotY
              0.5095f,  0.6526f,  0.3333f,  0.3634f,    26.2f,  0.45f,  0.16f,   // sig
              0.4928f,  0.5277f,  0.5913f,  0.4750f,    -1.7f,  0.50f,  0.50f,   // torso
              0.3454f,  0.4086f,  0.3282f,  0.3078f,    76.3f,  0.92f,  0.51f,   // arm_l
              0.6041f,  0.6206f,  0.4308f,  0.3847f,   -86.8f,  0.23f,  0.08f,   // arm_r
              0.4808f,  0.2942f,  0.5904f,  0.4300f,    10.3f,  0.51f,  0.77f,   // head
        },
        1f, 5.0f, 12.0f, false, false);

    /**
     * Kitty Buddy, whose signature part is its tail and whose torso is the dress.
     *
     * <p>Placed by the fitter rather than by hand: the head located in the flat sprite
     * by masked cross-correlation, which fixes the scale for every part, then the whole
     * body nudged until the assembly matches the sprite in colour, pixel by pixel.
     * Silhouette overlap 0.908 against the sprite it replaces.
     */
    static final Rig KITTY = new Rig(
        new int[] {
            R.drawable.buddy_kitty_part_sig,
            R.drawable.buddy_kitty_part_torso,
            R.drawable.buddy_kitty_part_arm_l,
            R.drawable.buddy_kitty_part_arm_r,
            R.drawable.buddy_kitty_part_head,
        },
        new float[] {
            //  cx        cy        w        h       rest    pivotX pivotY
              0.6034f,  0.6247f,  0.4374f,  0.2870f,     8.8f,  0.20f,  0.65f,   // sig
              0.4726f,  0.6668f,  0.5974f,  0.5165f,    -1.8f,  0.50f,  0.50f,   // torso
              0.3039f,  0.4692f,  0.2965f,  0.2432f,   104.2f,  0.92f,  0.60f,   // arm_l
              0.7034f,  0.6072f,  0.3428f,  0.2842f,   -55.3f,  0.08f,  0.10f,   // arm_r
              0.5346f,  0.3192f,  0.5251f,  0.4799f,   -13.7f,  0.44f,  0.86f,   // head
        },
        1f, 4.6f, 12.0f, false, false);

    /**
     * Cloud Pup, whose signature part is the pair of long ears — the widest of the
     * eight, and the hardest to key out of the head, because they are very nearly the
     * same blue as the head they hang off.
     *
     * <p>Its ears were drawn into the head bitmap as well as supplied loose. See
     * {@link #DINO} for what that cost and how it is undone.
     *
     * <p>Placed by the fitter rather than by hand: the head located in the flat sprite
     * by masked cross-correlation, which fixes the scale for every part, then the whole
     * body nudged until the assembly matches the sprite in colour, pixel by pixel.
     * Silhouette overlap 0.828 against the sprite it replaces.
     */
    static final Rig CLOUD = new Rig(
        new int[] {
            R.drawable.buddy_cloud_part_sig,
            R.drawable.buddy_cloud_part_torso,
            R.drawable.buddy_cloud_part_arm_l,
            R.drawable.buddy_cloud_part_arm_r,
            R.drawable.buddy_cloud_part_head,
        },
        new float[] {
            //  cx        cy        w        h       rest    pivotX pivotY
              0.4804f,  0.3246f,  0.8529f,  0.3910f,    15.3f,  0.51f,  0.56f,   // sig
              0.5177f,  0.5254f,  0.5492f,  0.4940f,    10.5f,  0.50f,  0.50f,   // torso
              0.4614f,  0.4463f,  0.4469f,  0.4124f,    76.3f,  0.60f,  0.48f,   // arm_l
              0.5739f,  0.6213f,  0.3637f,  0.3229f,  -102.5f,  0.31f,  0.08f,   // arm_r
              0.4927f,  0.3492f,  0.4234f,  0.3991f,    10.3f,  0.53f,  0.72f,   // head
        },
        1f, 3.4f, 8.0f, false, false);

    /**
     * Dino Buddy, whose signature part is the sprout growing out of its head.
     *
     * <p>The sprout was drawn INTO the head bitmap as well as supplied as a loose part,
     * and that is worse than it sounds. At rest the character looked right, because the
     * sprout was there — in the head. But head pixels cannot move, and the loose copy
     * was redundant, so the fitter (which scores colour against the flat sprite) put it
     * where it did least harm, which is off the character. The visible sprout could not
     * move and the movable one could not be seen: the signature animation animated
     * nothing. Same for the trike's frill and the cloud pup's ears.
     *
     * <p>Undone by keying the painted copy back out of the head — matched by
     * cross-correlation, erased only where the head agrees with the part about what
     * colour it is, so the trike's cheek survives the frill that was hiding it. Putting
     * the loose part back where the painted one sat reproduces the original head to
     * within about one per cent of its pixels, so the character is unchanged standing
     * still; the difference is that the part can now move.
     *
     * <p>Placed by the fitter rather than by hand: the head located in the flat sprite
     * by masked cross-correlation, which fixes the scale for every part, then the whole
     * body nudged until the assembly matches the sprite in colour, pixel by pixel.
     * Silhouette overlap 0.803 against the sprite it replaces.
     */
    static final Rig DINO = new Rig(
        new int[] {
            R.drawable.buddy_dino_part_sig,
            R.drawable.buddy_dino_part_torso,
            R.drawable.buddy_dino_part_arm_l,
            R.drawable.buddy_dino_part_arm_r,
            R.drawable.buddy_dino_part_head,
        },
        new float[] {
            //  cx        cy        w        h       rest    pivotX pivotY
              0.5805f,  0.1552f,  0.3475f,  0.2066f,    -2.5f,  0.34f,  0.92f,   // sig
              0.4638f,  0.6068f,  0.6570f,  0.4334f,    19.2f,  0.50f,  0.50f,   // torso
              0.3843f,  0.5965f,  0.2991f,  0.2906f,    72.8f,  0.87f,  0.19f,   // arm_l
              0.5423f,  0.7345f,  0.3249f,  0.3032f,   -55.2f,  0.35f,  0.08f,   // arm_r
              0.5258f,  0.4040f,  0.4959f,  0.4260f,     2.5f,  0.44f,  0.74f,   // head
        },
        1f, 3.0f, 7.0f, false, false);

    /**
     * Jungle Trike, whose signature part is the frill behind its head.
     *
     * <p>Its frill was drawn into the head bitmap as well as supplied loose. See
     * {@link #DINO} for what that cost and how it is undone. The frill is the awkward
     * one: it sits BEHIND the head, so most of it is hidden, and a match that scores a
     * hidden band as a mismatch settles for the top of the frill and nothing else.
     *
     * <p>Placed by the fitter rather than by hand: the head located in the flat sprite
     * by masked cross-correlation, which fixes the scale for every part, then the whole
     * body nudged until the assembly matches the sprite in colour, pixel by pixel.
     * Silhouette overlap 0.823 against the sprite it replaces.
     */
    static final Rig TRIKE = new Rig(
        new int[] {
            R.drawable.buddy_trike_part_sig,
            R.drawable.buddy_trike_part_torso,
            R.drawable.buddy_trike_part_arm_l,
            R.drawable.buddy_trike_part_arm_r,
            R.drawable.buddy_trike_part_head,
        },
        new float[] {
            //  cx        cy        w        h       rest    pivotX pivotY
              0.5359f,  0.2607f,  0.6347f,  0.4572f,    -6.0f,  0.47f,  0.66f,   // sig
              0.5047f,  0.5807f,  0.6658f,  0.4960f,    -7.0f,  0.50f,  0.50f,   // torso
              0.3158f,  0.5103f,  0.3582f,  0.2559f,   104.2f,  0.92f,  0.29f,   // arm_l
              0.6196f,  0.6734f,  0.4650f,  0.3322f,  -100.7f,  0.27f,  0.08f,   // arm_r
              0.5177f,  0.3335f,  0.4802f,  0.4344f,    -6.0f,  0.49f,  0.79f,   // head
        },
        1f, 2.6f, 5.0f, false, false);

    /**
     * Burger Buddy. The only one whose signature part is drawn IN FRONT of the
     * body: it is the cheeseburger being held, not a limb, and it does not move.
     *
     * <p>Placed by the fitter rather than by hand: the head located in the flat sprite
     * by masked cross-correlation, which fixes the scale for every part, then the whole
     * body nudged until the assembly matches the sprite in colour, pixel by pixel.
     * Silhouette overlap 0.894 against the sprite it replaces.
     */
    static final Rig BURGER = new Rig(
        new int[] {
            R.drawable.buddy_burger_part_sig,
            R.drawable.buddy_burger_part_torso,
            R.drawable.buddy_burger_part_arm_l,
            R.drawable.buddy_burger_part_arm_r,
            R.drawable.buddy_burger_part_head,
        },
        new float[] {
            //  cx        cy        w        h       rest    pivotX pivotY
              0.5870f,  0.6113f,  0.4199f,  0.3645f,    -3.5f,  0.26f,  0.71f,   // sig
              0.4870f,  0.6889f,  0.5871f,  0.3790f,   -15.7f,  0.50f,  0.50f,   // torso
              0.4710f,  0.4355f,  0.2480f,  0.2400f,    78.0f,  0.59f,  0.81f,   // arm_l
              0.5290f,  0.6855f,  0.2915f,  0.2839f,   -74.5f,  0.38f,  0.08f,   // arm_r
              0.5000f,  0.3288f,  0.5593f,  0.5317f,     6.0f,  0.49f,  0.84f,   // head
        },
        1f, 3.0f, 4.0f, false, true);
}
