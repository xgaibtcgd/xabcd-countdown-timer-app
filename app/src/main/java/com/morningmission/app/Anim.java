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
