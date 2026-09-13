package com.morningmission.app;

/**
 * What the Mission Complete screen does to celebrate, which is a different thing
 * each morning.
 *
 * <p>The screen had one celebration: two confetti cannons, a gold sunburst, and seven
 * stars orbiting the buddy. It is fine, and a child doing this every morning has seen
 * it by the end of the first week. There are five now and the morning picks one.
 *
 * <h2>Everything here is a pure function</h2>
 *
 * <p>No state, no Android types, no drawing. The screen asks this class how bright a
 * thing should be at time {@code t} and draws it; this class never draws anything
 * itself. That is not tidiness for its own sake -- it is what lets tools/SelfTest.java
 * sample the brightness of every effect across a long run and ASSERT how fast it
 * flashes, which is the one property here that actually matters to get right.
 *
 * <h2>Flashing</h2>
 *
 * <p>Fireworks and a disco are exactly the kind of effect that carries a
 * photosensitivity risk, and this is an app pointed at a five-year-old first thing in
 * the morning. Every envelope below is held under {@link #MAX_FLASH_HZ}, which is under
 * the WCAG general flash threshold of three flashes a second, and none of them drives a
 * full-screen luminance change -- the disco wash and the firework flash are both local
 * and both well short of the screen area at which flash guidance starts to apply.
 *
 * <p>{@link #MODE_STARFALL} has no flash in it at all, deliberately, so that whatever
 * else the rotation does there is always one morning in five that does nothing sudden.
 *
 * <p>The rates are asserted rather than trusted. It is easy to nudge a sine's frequency
 * while tuning how something looks and not notice that it has crossed a line that is
 * not about looks.
 */
final class Celebration {

    private Celebration() {}

    /** Today's: cannons of paper, now re-firing in waves rather than once. */
    static final int MODE_CONFETTI = 0;
    /** Shells rise from under the stage and burst into a ring. */
    static final int MODE_FIREWORKS = 1;
    /** Light cones sweep, the floor washes through colour, the scene breathes. */
    static final int MODE_DISCO = 2;
    /** A fairground border of bulbs running round the stage. */
    static final int MODE_CHASE = 3;
    /** The gentle one: a slow drift of twinkling stars and no flash anywhere. */
    static final int MODE_STARFALL = 4;
    static final int MODE_COUNT = 5;

    /** Names, in order, for failure messages and the design preview. */
    static final String[] NAMES = { "confetti", "fireworks", "disco", "chase", "starfall" };

    /**
     * The fastest any brightness in here is allowed to change, in cycles a second.
     *
     * <p>Under the WCAG general flash threshold of three. Nothing here needs to be
     * faster, and the two modes that would plausibly have been written faster -- a
     * strobing disco, a crackling firework -- are the two the threshold exists for.
     */
    static final float MAX_FLASH_HZ = 2.6f;

    // ------------------------------------------------------------------ which one

    /**
     * Which celebration this morning gets.
     *
     * <p>Fed from {@link Engine#routeSeed()}, which is rolled at the two edges that mean
     * a new morning and survives a pause, a resume, a poke and a completed task -- the
     * same properties the route needs, for the same reason. It goes through its own
     * mixing constant so the two are independent: a morning that draws a particular
     * route should not thereby always draw the same lights.
     */
    static int modeFor(long seed) {
        int h = (int) (seed ^ (seed >>> 32));
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;                   // a different constant from Route.seedFrom
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return Math.floorMod(h, MODE_COUNT);
    }

    // ------------------------------------------------------------------ envelopes

    /**
     * How bright the mode's background lighting is at time {@code t}, 0 to 1.
     *
     * <p>One function for all five so there is one thing to hold to the flash limit
     * rather than five things that each look fine alone. A mode with no background
     * lighting returns a constant, which is not a flash at any rate.
     */
    static float glow(int mode, float t) {
        switch (mode) {
            case MODE_DISCO:
                // The room breathing. 1.6 Hz, and it never drops below half, so the
                // change in brightness is a swell rather than a blink.
                return 0.72f + 0.28f * wave(t, 1.6f);
            case MODE_CHASE:
                // The bulbs come up and down together under the chase, slowly.
                return 0.80f + 0.20f * wave(t, 0.9f);
            case MODE_FIREWORKS:
                // The sky itself does not pulse; only the individual bursts light up,
                // and those are local and handled by burstFlash below.
                return 1f;
            default:
                return 1f;
        }
    }

    /**
     * How bright one firework's flash is, {@code age} seconds after it burst.
     *
     * <p>A hard onset and a fast decay, which is what a firework looks like, but the
     * onset is over {@link #FLASH_RISE} rather than instantaneous -- an instantaneous
     * full-brightness onset is a flash in the sense the guidance means, and a 90ms ramp
     * is indistinguishable to look at.
     */
    static float burstFlash(float age) {
        if (age <= 0f || age >= FLASH_LIFE) return 0f;
        if (age < FLASH_RISE) return age / FLASH_RISE;
        float fall = (age - FLASH_RISE) / (FLASH_LIFE - FLASH_RISE);
        return (1f - fall) * (1f - fall);
    }

    static final float FLASH_RISE = 0.09f, FLASH_LIFE = 0.62f;

    /**
     * Where the disco's colour wash has got to, 0 to 1 around the hue wheel.
     *
     * <p>Hue, not brightness. A wash that cycles colour at constant luminance is not a
     * flash however fast it goes, which is why the disco can feel busy without being
     * the thing the limit is about.
     */
    static float hueCycle(float t) {
        float p = t * 0.19f;
        return p - (float) Math.floor(p);
    }

    /** Where the chase's lit bulb has got to, 0 to 1 around the border. */
    static float chasePhase(float t) {
        float p = t * 0.42f;
        return p - (float) Math.floor(p);
    }

    /** Which way the disco's light cones are pointing, in degrees, cone {@code i} of n. */
    static float coneAngle(int i, int n, float t) {
        return i * (360f / n) + 26f * wave(t, 0.31f);
    }

    /**
     * A 0..1 sine at {@code hz}, and the single place a frequency is written down.
     *
     * <p>Every envelope above goes through here, so there is one function to hold to
     * {@link #MAX_FLASH_HZ} and one place a tuning session can put a number that is too
     * big. tools/SelfTest.java samples the envelopes rather than reading the constants,
     * so a rate smuggled past this helper is still caught.
     */
    private static float wave(float t, float hz) {
        return 0.5f + 0.5f * (float) Math.sin(t * hz * 2.0 * Math.PI);
    }

    /**
     * How far the lights go down for this mode, 0 to 1.
     *
     * <p>Rendered without this, all five modes looked identical. The celebration sky is
     * near-white gold, and a light drawn on near-white is not a light -- the disco cones
     * and the firework flashes simply were not there. A party needs the lights down
     * first, which is also what makes each mode announce itself the moment the screen
     * arrives rather than being a detail you would have to hunt for.
     *
     * <p>Confetti and starfall stay in daylight. They are made of solid colour on a
     * bright ground and they read fine; dimming those would be dimming for its own sake.
     *
     * <p>This is a real luminance change, and a large one, so: it happens ONCE, it ramps
     * over {@link #DUSK_RISE}, and it never reverses. A single monotone transition is
     * not a flash at any threshold -- what the guidance is about is repetition, and
     * tools/SelfTest.java holds this to being monotonic as well as bounded.
     */
    static float dusk(int mode, float t) {
        float target;
        switch (mode) {
            case MODE_DISCO:     target = 0.62f; break;
            case MODE_CHASE:     target = 0.46f; break;
            case MODE_FIREWORKS: target = 0.52f; break;
            default:             return 0f;
        }
        if (t <= 0f) return 0f;
        if (t >= DUSK_RISE) return target;
        float p = t / DUSK_RISE;
        return target * p * p * (3f - 2f * p);           // smoothstep, no hard edge
    }

    /** How long the lights take to go down. Slow enough to read as a dim, not a cut. */
    static final float DUSK_RISE = 0.85f;

    /** The colour the lights go down to: a deep evening blue, not black. */
    static final int DUSK_COLOUR = 0xFF141A3A;

    // ------------------------------------------------------------------ budget

    /**
     * How many particles a mode's opening volley spends.
     *
     * <p>{@code Particles} holds 220 and drops silently once it is full, so a mode that
     * overspends does not crash -- it stops looking like anything, which is worse to
     * find. Confetti alone spends 168 of the 220 in one call.
     *
     * <p>This is the declared budget for {@code ScreenComplete.openVolley}, and SelfTest
     * holds the two together by firing each opening into a pool and counting what comes
     * out. It used to be neither: nothing called it but the gate, and the gate compared
     * it against a constant, while the app threw the same 168-piece cannon whatever the
     * morning had picked. Three of these five numbers described a volley no code path
     * produced.
     */
    static int volley(int mode) {
        switch (mode) {
            case MODE_CONFETTI:  return 168;   // the two cannons and the drizzle
            case MODE_FIREWORKS: return 0;     // nothing at the start; shells arrive
            case MODE_DISCO:     return 90;    // a light scatter under the lights
            case MODE_CHASE:     return 110;
            case MODE_STARFALL:  return 60;
            default:             return 0;
        }
    }

    /** Particles one firework shell spends when it bursts. At most three are alive. */
    static final int SHELL_PIECES = 34;
    static final int SHELLS = 3;
}
