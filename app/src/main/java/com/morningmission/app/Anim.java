package com.morningmission.app;

/**
 * How the buddy moves.
 *
 * <p>The old build nominally had ten animation states but produced six distinct motions:
 * Get Dressed, Breakfast, Brush Teeth, Shoes On and Backpack all shared one identical
 * branch, and Wake Up, Bathroom, Brush Hair, Wash Face, Jacket, Feed Pet and Vitamins had
 * no branch at all. The only thing that actually differed per task was a static emoji
 * pinned at a fixed offset -- and three of the twelve tasks did not even get that. A child
 * brushing their teeth saw the same motion as a child putting on shoes.
 *
 * <p>Every task now has its own motion, states cross-fade instead of snapping, and squash
 * and stretch is derived from vertical velocity so a landing reads as a landing.
 *
 * <p>Free of Android imports so the motion table can be exercised off-device.
 */
final class Anim {

    private Anim() {}

    // States 0..11 line up with Art.ACT_*, so a task key maps straight to its motion.
    static final int SLEEPY = Art.ACT_COUNT;
    static final int CHEER = Art.ACT_COUNT + 1;
    static final int HURRY = Art.ACT_COUNT + 2;
    static final int DANCE = Art.ACT_COUNT + 3;
    static final int IDLE = Art.ACT_COUNT + 4;
    static final int STATE_COUNT = Art.ACT_COUNT + 5;

    /** How long a change of state takes to blend, in seconds. */
    private static final float BLEND_SECONDS = 0.28f;

    /** The result of solving a state at a moment in time. */
    static final class Transform {
        /** Offsets in design units. */
        float dx, dy;
        /** Degrees. */
        float rotation;
        /** Scale, about the feet rather than the centre. */
        float scaleX = 1f, scaleY = 1f;

        void reset() {
            dx = 0f; dy = 0f; rotation = 0f; scaleX = 1f; scaleY = 1f;
        }

        void lerpFrom(Transform a, Transform b, float t) {
            dx = a.dx + (b.dx - a.dx) * t;
            dy = a.dy + (b.dy - a.dy) * t;
            rotation = a.rotation + (b.rotation - a.rotation) * t;
            scaleX = a.scaleX + (b.scaleX - a.scaleX) * t;
            scaleY = a.scaleY + (b.scaleY - a.scaleY) * t;
        }
    }

    /**
     * Kept local rather than borrowed from {@link Theme}: this class must not touch
     * anything that loads native graphics state, so the motion table stays checkable
     * off-device. Theme's static initialiser reaches Typeface, which is native.
     */
    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float sin(float t) { return (float) Math.sin(t); }

    private static float cos(float t) { return (float) Math.cos(t); }

    /** 0..1 sawtooth with the given period. */
    private static float phase(float t, float period) {
        float v = (t / period) % 1f;
        return v < 0f ? v + 1f : v;
    }

    private static float easeOutBack(float p) {
        float c = 1.70158f;
        float u = p - 1f;
        return 1f + (c + 1f) * u * u * u + c * u * u;
    }

    private static float easeInOutCubic(float p) {
        return p < 0.5f ? 4f * p * p * p : 1f - (float) Math.pow(-2f * p + 2f, 3) / 2f;
    }

    /**
     * Fills {@code out} with the motion for one state at time {@code t} (seconds).
     *
     * <p>Amplitudes are in design units, where the buddy is roughly 360 across.
     */
    static void solve(int state, float t, Transform out) {
        out.reset();
        switch (state) {
            case Art.ACT_WAKE: {
                // A long stretch upward out of a squash, then a held yawn-tilt.
                float p = phase(t, 3.4f);
                float rise = easeInOutCubic(Math.min(1f, p * 2.2f));
                out.scaleY = 0.88f + 0.14f * rise;
                out.scaleX = 2f - out.scaleY;
                out.dy = -14f * rise + sin(t * 1.1f) * 3f;
                out.rotation = 6f * rise * (p > 0.5f ? 1f : p * 2f);
                break;
            }
            case Art.ACT_BATH: {
                // A shuffle from side to side with a small hop.
                out.dx = sin(t * 2.2f) * 22f;
                float hop = phase(t, 1.8f);
                out.dy = hop < 0.3f ? -26f * (float) Math.sin(hop / 0.3f * Math.PI) : 0f;
                out.rotation = sin(t * 2.2f) * 4f;
                break;
            }
            case Art.ACT_DRESS: {
                // Two quick pull-on squashes per cycle.
                float squash = Math.abs(sin(t * 3.1f));
                out.scaleY = 1f - 0.10f * squash;
                out.scaleX = 2f - out.scaleY;
                out.dy = -8f * (1f - squash);
                out.rotation = sin(t * 1.6f) * 3f;
                break;
            }
            case Art.ACT_EAT: {
                // A forward lean with a bite pulse.
                out.rotation = sin(t * 2.6f) * 8f;
                float bite = phase(t, 1.1f);
                float pulse = bite < 0.22f ? (float) Math.sin(bite / 0.22f * Math.PI) : 0f;
                out.scaleX = 1f + 0.06f * pulse;
                out.scaleY = 1f + 0.04f * pulse;
                out.dy = sin(t * 1.4f) * 6f;
                break;
            }
            case Art.ACT_BRUSH: {
                // High-frequency jitter: unmistakably brushing.
                out.dx = sin(t * 11f) * 9f;
                out.rotation = sin(t * 11f) * 2.5f;
                out.dy = sin(t * 2.2f) * 5f;
                break;
            }
            case Art.ACT_HAIR: {
                // A slow head-tilt arc.
                out.rotation = sin(t * 1.3f) * 13f;
                out.dx = sin(t * 1.3f) * 8f;
                out.dy = sin(t * 2.6f) * 4f;
                break;
            }
            case Art.ACT_WASH: {
                // A tight circular scrub.
                out.dx = cos(t * 6f) * 7f;
                out.dy = sin(t * 6f) * 7f;
                out.rotation = sin(t * 6f) * 3f;
                break;
            }
            case Art.ACT_SHOES: {
                // Hopping on one foot; the lean flips with each hop.
                float hop = Math.abs(sin(t * 2.4f));
                out.dy = -26f * hop;
                out.rotation = (sin(t * 1.2f) > 0f ? 1f : -1f) * 7f * hop;
                out.scaleY = 1f + 0.05f * hop;
                out.scaleX = 2f - out.scaleY;
                break;
            }
            case Art.ACT_PACK: {
                // A shoulder shrug that settles.
                float p = phase(t, 1.4f);
                float shrug = easeOutBack(Math.min(1f, p * 1.6f));
                out.scaleY = 1f - 0.07f * (1f - shrug) + 0.04f * shrug;
                out.scaleX = 2f - out.scaleY;
                out.dy = -10f * shrug;
                out.rotation = sin(t * 1.8f) * 3f;
                break;
            }
            case Art.ACT_JACKET: {
                // Idle, then a spin into the sleeve every five seconds.
                float p = phase(t, 5f);
                if (p < 0.32f) {
                    float spin = p / 0.32f;
                    out.rotation = 360f * easeInOutCubic(spin);
                    out.dy = -18f * (float) Math.sin(spin * Math.PI);
                } else {
                    out.dy = sin(t * 1.5f) * 7f;
                    out.rotation = sin(t * 1.9f) * 3f;
                }
                break;
            }
            case Art.ACT_PET: {
                // Crouched down, leaning side to side.
                out.scaleY = 0.94f;
                out.scaleX = 2f - out.scaleY;
                out.dx = sin(t * 4f) * 14f;
                out.dy = 6f + sin(t * 2f) * 4f;
                out.rotation = sin(t * 4f) * 5f;
                break;
            }
            case Art.ACT_VITAMIN: {
                // One big hop with a snap at the top.
                float p = phase(t, 2f);
                float hop = p < 0.5f ? easeOutBack(p * 2f) : 1f - easeInOutCubic((p - 0.5f) * 2f);
                out.dy = -34f * hop;
                out.scaleY = 1f + 0.06f * hop;
                out.scaleX = 2f - out.scaleY;
                break;
            }
            case SLEEPY: {
                // Barely moving: a slow breath.
                out.dy = sin(t * 0.55f) * 5f;
                out.scaleY = 0.975f + sin(t * 0.5f) * 0.02f;
                out.scaleX = 2f - out.scaleY;
                break;
            }
            case CHEER: {
                // Repeated hops, always upward from the resting line.
                float hop = Math.abs(sin(t * 4.2f));
                out.dy = -30f * hop;
                out.rotation = sin(t * 3.1f) * 5f;
                out.scaleY = 1f + 0.05f * hop;
                out.scaleX = 2f - out.scaleY;
                break;
            }
            case HURRY: {
                // Fast, slightly frantic.
                out.dy = sin(t * 8.5f) * 11f;
                out.rotation = sin(t * 7.6f) * 8f;
                out.dx = sin(t * 4.1f) * 6f;
                break;
            }
            case DANCE: {
                // The victory dance: bob, sway and squash, each on its own phase so it
                // does not read as one stiff oscillation.
                out.dy = sin(t * 6.4f) * 20f;
                out.rotation = sin(t * 4.6f) * 10f;
                out.dx = sin(t * 2.3f) * 16f;
                out.scaleX = 1.02f + sin(t * 7.4f) * 0.04f;
                out.scaleY = 2f - out.scaleX;
                break;
            }
            default: {
                // Idle float.
                out.dy = sin(t * 1.15f) * 7f;
                out.rotation = sin(t * 0.8f) * 2f;
                break;
            }
        }
    }

    /**
     * Cross-fades between states so a change of task does not snap.
     *
     * <p>Blending the transform rather than the pixels costs nothing: no extra layer, no
     * second draw.
     */
    static final class Blend {
        private final Transform fromTransform = new Transform();
        private final Transform toTransform = new Transform();
        private int from = IDLE;
        private int to = IDLE;
        private float progress = 1f;

        private float previousDy;
        private float velocity;

        int state() { return to; }

        void set(int state) {
            if (state == to) return;
            from = to;
            to = state;
            progress = 0f;
        }

        /** Jumps straight to a state with no blend, for a fresh screen. */
        void snap(int state) {
            from = state;
            to = state;
            progress = 1f;
        }

        void update(float dt) {
            if (progress < 1f) {
                progress = Math.min(1f, progress + dt / BLEND_SECONDS);
            }
        }

        /**
         * Solves the blended motion, then layers squash and stretch derived from vertical
         * velocity. The pivot is the buddy's feet, not its centre: scaling about the
         * centre reads as inflating rather than landing.
         */
        void solve(float t, float dt, Transform out) {
            if (progress >= 1f) {
                Anim.solve(to, t, out);
            } else {
                Anim.solve(from, t, fromTransform);
                Anim.solve(to, t, toTransform);
                out.lerpFrom(fromTransform, toTransform, easeInOutCubic(progress));
            }

            if (dt > 1e-4f) {
                float instant = (out.dy - previousDy) / dt;
                // Smooth, or a single long frame produces a visible pop.
                velocity += (instant - velocity) * Math.min(1f, dt * 12f);
                previousDy = out.dy;
                float stretch = clamp(1f - velocity * 0.00022f, 0.88f, 1.12f);
                out.scaleY *= stretch;
                out.scaleX *= 2f - stretch;
            }
        }
    }

    // ------------------------------------------------------------------- idle drift

    /** The same hash Scene uses, so the app and the preview stagger identically. */
    private static float hash(int seed) {
        int h = seed * 0x27D4EB2D;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return (h >>> 8) / (float) (1 << 24);
    }

    /**
     * A gentle idle float for a round control.
     *
     * <p>Two unrelated frequencies give a slow wandering path rather than a bob, a
     * breath scales it, and a low-frequency envelope lets one control at a time bump a
     * little more than its neighbours so a row of them does not pulse in unison.
     *
     * <p>The offsets are bounded by {@code limit} in both axes, which is the whole point:
     * the minute bubbles sit a few units apart, so a caller can pass the gap it actually
     * has and be sure nothing collides. Scale stays inside 0.97..1.03.
     */
    static void drift(int seed, float t, float limit, Transform out) {
        out.reset();
        if (limit <= 0f) return;
        float phase = hash(seed) * 6.2831855f;
        float breathPhase = hash(seed + 977) * 6.2831855f;

        // 0.61 and 0.43 rad/s are deliberately not a ratio of small integers, so the
        // path drifts around its cell instead of retracing one line.
        out.dx = sin(t * 0.43f + phase) * limit * 0.62f;
        out.dy = (sin(t * 0.61f + phase * 1.7f) * 0.7f
                  + sin(t * 1.13f + phase) * 0.3f) * limit;

        // The bump: a slow envelope that spends most of its time near zero, so each
        // control swells briefly on its own schedule rather than everything breathing
        // together.
        float envelope = Math.max(0f, sin(t * 0.37f + breathPhase));
        float swell = envelope * envelope * envelope;
        float scale = 1f + 0.03f * swell - 0.008f * (1f - swell);
        out.scaleX = scale;
        out.scaleY = scale;
        out.dx = clamp(out.dx, -limit, limit);
        out.dy = clamp(out.dy, -limit, limit);
    }

    // ------------------------------------------------------------ collectible actions
    // What the buddy does when it reaches something on the trail. These are one-shot
    // beats driven by a 0..1 progress rather than looping states, and they compose on
    // top of whatever walk motion is already running -- the offsets add, the scales
    // multiply. Each buddy gets a motion that suits it; a shark does not nibble.

    static final int FEAST_BITE   = 0;   // lean in and take a bite
    static final int FEAST_SIP    = 1;   // hover down, sip, rise
    static final int FEAST_POUNCE = 2;   // crouch, leap, land
    static final int FEAST_LUNGE  = 3;   // a big forward surge, nose down
    static final int FEAST_STOMP  = 4;   // rear back, slam down
    static final int FEAST_SPIN   = 5;   // rise and turn
    static final int FEAST_NIBBLE = 6;   // three quick little nods
    static final int FEAST_TOSS   = 7;   // horns under it, then a flick of the head
    static final int FEAST_COUNT  = 8;

    /** A 0..1..0 hump, peaking at {@code peak}. */
    private static float hump(float p, float peak) {
        if (p <= 0f || p >= 1f) return 0f;
        float u = p < peak ? p / peak : 1f - (p - peak) / (1f - peak);
        return (float) Math.sin(u * Math.PI * 0.5f);
    }

    /**
     * Fills {@code out} with one collectible action.
     *
     * @param kind one of the FEAST_* constants; anything else is treated as a bite
     * @param p    0 at the start of the wind-up, 1 when the buddy is walking again
     */
    static void feast(int kind, float p, Transform out) {
        feast(kind, p, 1f, out);
    }

    /**
     * The same, scaled.
     *
     * <p>A collectible is eaten in three goes, and three identical lunges read as a
     * stutter rather than as eating. The caller runs this three times across the beat at
     * decreasing strength: one committed chomp, then two quick follow-ups. It also keeps
     * a spinner from turning three full times in two seconds.
     *
     * @param strength 0 for no motion, 1 for the full move
     */
    static void feast(int kind, float p, float strength, Transform out) {
        feastMove(kind, p, out);
        if (strength == 1f) return;
        float k = clamp(strength, 0f, 1f);
        out.dx *= k;
        out.dy *= k;
        out.rotation *= k;
        out.scaleX = 1f + (out.scaleX - 1f) * k;
        out.scaleY = 1f + (out.scaleY - 1f) * k;
    }

    private static void feastMove(int kind, float p, Transform out) {
        out.reset();
        if (p <= 0f || p >= 1f) return;
        // Contact is a third of the way in: the wind-up is short, the recovery longer.
        float contact = 0.34f;
        switch (kind) {
            case FEAST_SIP: {
                // Hovering: up first, then a quick dip onto the item, wings buzzing.
                float dip = hump(p, contact);
                out.dy = -30f + 46f * dip + sin(p * 80f) * 3f;
                out.dx = 12f * dip;
                out.rotation = 8f * dip;
                break;
            }
            case FEAST_POUNCE: {
                if (p < contact) {                       // crouch
                    float c = p / contact;
                    out.scaleY = 1f - 0.16f * c;
                    out.dy = 6f * c;
                } else {                                  // leap and land
                    float j = (p - contact) / (1f - contact);
                    out.dy = -110f * (float) Math.sin(j * Math.PI);
                    out.dx = 40f * (float) Math.sin(j * Math.PI);
                    out.scaleY = 1f + 0.14f * (float) Math.sin(j * Math.PI)
                               - 0.18f * Math.max(0f, (j - 0.82f) / 0.18f);
                }
                out.scaleX = 2f - out.scaleY;
                break;
            }
            case FEAST_LUNGE: {
                // A surge forward, nose down, then a snap back.
                float surge = hump(p, contact);
                out.dx = 88f * surge;
                out.rotation = 13f * surge;
                out.scaleX = 1f + 0.12f * surge;
                out.scaleY = 2f - out.scaleX;
                break;
            }
            case FEAST_STOMP: {
                if (p < contact) {                        // rear back
                    float c = p / contact;
                    out.rotation = -11f * c;
                    out.dy = -26f * c;
                } else {                                   // slam
                    float j = (p - contact) / (1f - contact);
                    float land = Math.min(1f, j * 3.2f);
                    out.rotation = -11f + 15f * land;
                    out.dy = -26f + 26f * land;
                    out.scaleY = 1f - 0.20f * Math.max(0f, 1f - Math.abs(j * 3.2f - 1f) * 2.4f);
                    out.scaleX = 2f - out.scaleY;
                }
                break;
            }
            case FEAST_SPIN: {
                float rise = hump(p, 0.5f);
                out.dy = -58f * rise;
                out.rotation = 360f * easeInOutCubic(p);
                out.dx = 18f * rise;
                break;
            }
            case FEAST_TOSS: {
                // A horned animal does not lean in and bite, it scoops. The head goes
                // down and forward to get the horns under the thing, then snaps up and
                // back to throw it -- two peaks a fifth of the beat apart, which is what
                // keeps the flick reading as a separate movement from the dip rather
                // than one long nod.
                float dip = hump(p, contact);
                float flick = Math.max(0f, 1f - Math.abs(p - 0.55f) * 5.5f);
                out.dx = 30f * dip;
                out.dy = 8f * dip - 34f * flick;
                out.rotation = 15f * dip - 26f * flick;
                out.scaleY = 1f + 0.10f * flick;
                out.scaleX = 2f - out.scaleY;
                break;
            }
            case FEAST_NIBBLE: {
                // Three small quick nods, no leaving the ground.
                float nods = (float) Math.sin(p * Math.PI * 6f) * hump(p, 0.5f);
                out.dy = -9f * Math.abs(nods);
                out.dx = 20f * hump(p, contact);
                out.rotation = 6f * nods;
                break;
            }
            case FEAST_BITE:
            default: {
                float lean = hump(p, contact);
                out.dx = 46f * lean;
                out.rotation = 9f * lean;
                // The squash on contact is what sells the bite.
                float chomp = Math.max(0f, 1f - Math.abs(p - contact) * 9f);
                out.scaleY = 1f - 0.13f * chomp;
                out.scaleX = 2f - out.scaleY;
                break;
            }
        }
    }

    /**
     * Picks the state the buddy should be in.
     *
     * @param cheerRemaining seconds left of a completion cheer, or zero
     */
    static int stateFor(Engine engine, boolean danceEnabled, float cheerRemaining,
                        boolean running) {
        if (engine.allDone()) return danceEnabled ? DANCE : CHEER;
        if (cheerRemaining > 0f) return CHEER;
        if (!running) return SLEEPY;
        if (engine.isLowTime()) return HURRY;
        return Art.activityKind(engine.activeKey());
    }
}
