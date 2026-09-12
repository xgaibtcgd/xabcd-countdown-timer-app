package com.morningmission.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The countdown: the buddy travels through its world collecting things on the way to a
 * goal, while the child works through the routine.
 */
final class ScreenAdventure extends Screen {

    private static final int R_BACK = 1, R_PAUSE = 2, R_ACTION = 3, R_MUTE = 4, R_ROUTE = 5;

    private final RectF scratch = new RectF();

    /** This morning's way to the chest. See {@link Route}. */
    private final Route route = new Route();

    /**
     * How far in front of the buddy's centre its mouth is, as a fraction of its height.
     *
     * <p>A treat has to land at the muzzle, not behind a sprite half the screen wide --
     * drawn on the buddy's own centre the bites, which are the whole point of them, were
     * never visible. Burger Buddy is already holding a burger of its own.
     *
     * <p>"In front" now means along the route rather than to the right, because the route
     * turns.
     *
     * <p>Package-visible, with {@link #MOUTH_UP}, only so tools/SelfTest.java can hold
     * the buddy the right distance behind its own mouth using these numbers rather than
     * a second copy of them. A copy of a formula in a test is how the last change shipped
     * a test still pointing at where the buddy no longer was.
     */
    static final float MOUTH_AHEAD = 0.30f;

    /**
     * How far up the body the mouth is, as a fraction of its height.
     *
     * <p>A waypoint is a place to STAND; the treat floats this far above it, at about
     * the height the buddy's hands are, so reaching one is a lean rather than a squat --
     * at ankle height no amount of tilt looked like eating.
     *
     * <p>Every row lifts by the same fraction of the same base height, so the rows stay
     * exactly a cell apart and only the field as a whole moves up. Lifting the BUDDY
     * instead -- standing it below the treat rather than the treat above it -- is the
     * same picture and puts the front row's feet through the progress bar on a short
     * screen, which is how this came to be written down.
     */
    static final float MOUTH_UP = 0.38f;

    /** How far either side of the buddy a tap still counts as a poke, in body heights. */
    private static final float POKE_REACH = 0.45f;

    /** Dots drawn between one waypoint and the next, marking the way. */
    private static final int TRAIL_DOTS = 3;

    /**
     * How far short of the chest the buddy stops, in cells.
     *
     * <p>The chest is the last waypoint and it is drawn in front of everything, so a
     * buddy that walked all the way onto it spent the finale -- the lid, the star, the
     * confetti -- almost entirely hidden behind it. The old lane had the same problem the
     * other way round and solved it by parking the chest past the end of the walk; here
     * the buddy simply stops beside it.
     *
     * <p>Only the last segment is affected, and only the chest, which is never eaten:
     * every drop still meets the mouth exactly on its own beat.
     */
    private static final float CHEST_STANDOFF = 0.34f;

    /**
     * Seconds a poke lasts. Long enough for the buddy to complete its own move, short
     * enough that a child jabbing at the screen gets a reaction to each jab.
     */
    private static final float POKE_SECONDS = 0.55f;

    /** Seconds of quiet after which a run of pokes is over and the count starts again. */
    private static final float POKE_STREAK_RESET = 2.4f;
    /** How often a hurrying buddy throws off a bead of sweat. */
    private static final float SWEAT_SECONDS = 2.2f;

    /** Seconds left of the current poke, counted down in {@link #draw}. */
    private float pokeRemaining;
    /** How many pokes in a row. Keep going and the reaction grows. */
    private int pokeStreak;
    /** Seconds since the last poke, which is what ends a streak. */
    private float pokeIdle = POKE_STREAK_RESET;
    /** One dust puff per stomp landing, not one per frame of it. */
    private boolean stompPuffed;
    private float sweatTimer;

    /** How long the star takes to rise out of the chest once it opens. */
    /**
     * How long the chest takes to swing open.
     *
     * <p>Package-visible only so tools/SelfTest.java can hold it against
     * {@link Engine#prizeLeadMs()} on the shortest morning the app allows -- a chest that
     * takes longer to open than the buddy spends standing at it never finishes opening.
     */
    static final float PRIZE_SECONDS = 1.4f;
    /** Edge-detects the chest opening, so the star flies once and not every frame. */
    private boolean prizeOpened;
    private float prizeRemaining;

    /**
     * Which way the buddy is looking.
     *
     * <p>Held rather than recomputed because a segment that runs straight up or down the
     * screen has no horizontal direction to take it from, and a character that snapped
     * back to facing right every time it turned a corner would read as a glitch. It keeps
     * whichever way it was last actually going.
     */
    private boolean faceLeft;

    /** Fires the pickup burst once per item rather than on every frame of the window. */
    private int burstedThrough = 0;
    private final int[] burstPalette = new int[4];

    ScreenAdventure(MorningView view) {
        super(view);
    }

    @Override void onEnter() {
        burstedThrough = view.engine.collectedCount();
        prizeOpened = view.engine.atPrize();
        prizeRemaining = 0f;
        pokeRemaining = 0f;
        pokeStreak = 0;
        pokeIdle = POKE_STREAK_RESET;
        sweatTimer = 0f;
        faceLeft = false;
    }

    @Override void layout(Layout layout, HitMap hits) {
        BuddyTheme entering = view.buddy();
        route.layoutFor(layout, view.engine, entering.index,
                        entering.temperament.hover > 0f);
        // The route goes down FIRST. The buddy travels it continuously and the hit map is
        // rebuilt only on entry, resize, scroll and task completion -- never per frame --
        // so a region cannot follow the buddy. This is the whole area it moves through;
        // onPressDown does the distance test against where the buddy actually is.
        // Registering it first means the chips and the button, which are added after and
        // win where they overlap, keep their taps.
        routeBounds(layout, route, scratch);
        hits.add(R_ROUTE, scratch);

        hits.addPadded(R_BACK, layout.advBack, layout.minTouchUnits(), 0);
        hits.addPadded(R_PAUSE, layout.advPause, layout.minTouchUnits(), 0);
        hits.addPadded(R_MUTE, layout.advMute, layout.minTouchUnits(), 0);
        hits.add(R_ACTION, layout.advAction);
    }

    @Override void draw(Canvas c, Layout layout, float t, float dt) {
        BuddyTheme theme = view.buddy();
        Engine engine = view.engine;
        // Cheap enough to redo every frame -- a few divisions -- and it only rebuilds the
        // path itself when the seed or the grid changes, which is once a morning.
        route.layoutFor(layout, engine, theme.index, theme.temperament.hover > 0f);
        if (pokeRemaining > 0f) pokeRemaining = Math.max(0f, pokeRemaining - dt);
        pokeIdle += dt;
        sweatTimer += dt;
        if (prizeRemaining > 0f) prizeRemaining = Math.max(0f, prizeRemaining - dt);
        if (!engine.atPrize()) {
            prizeOpened = false;                    // a fresh morning
        } else if (!prizeOpened) {
            prizeOpened = true;
            prizeRemaining = PRIZE_SECONDS;
            firePrizeBurst(layout, theme, engine);
        }

        view.scene.drawBackground(c, theme, t);
        // The way to the chest, under everything that stands on it.
        drawDots(c, layout, theme, engine);
        // Depth order: a treat further up the screen than the buddy's mouth is further
        // away, so it goes behind. The one in its mouth is always in front, because the
        // bites come out of the side the buddy is standing on.
        drawTrail(c, layout, theme, engine, t, false);
        drawBuddy(c, layout, theme, engine, t);
        drawTrail(c, layout, theme, engine, t, true);
        // The chest last of all. Drawn before the buddy it spent the finale hidden
        // behind it, which is the one moment its lid and the star climbing out of it are
        // the thing worth looking at. Treats still to come pass behind it, as they did.
        drawGoal(c, layout, theme, engine);
        view.scene.drawForeground(c, theme, t, true);

        drawTopBar(c, layout, theme);
        drawMute(c, layout, theme);
        drawClock(c, layout, theme, engine);
        drawTally(c, layout, theme, engine);
        drawProgress(c, layout, theme, engine);
        drawTaskCard(c, layout, theme, engine);
        drawAction(c, layout, engine);

        view.particles.draw(c);

        if (engine.isPaused()) drawPausedVeil(c, layout);
    }

    /**
     * The buddy threads the route as the morning passes.
     *
     * <p>The old build parked it at a fixed x while the goal sat at the far right, so it
     * never actually approached the thing it was supposed to be travelling toward --
     * despite the README describing exactly that. Then it walked a straight line. Now it
     * turns corners, which is why it has to be told which way it is looking and how big
     * it is on the row it is standing on.
     */
    private void drawBuddy(Canvas c, Layout layout, BuddyTheme theme, Engine engine, float t) {
        float travelled = travelled(engine);
        float x = walkX(layout, engine, route)
                + (float) Math.sin(t * 1.5f) * layout.advScene.width() * 0.016f;
        float feet = walkY(layout, engine, route);
        float height = bodyHeight(layout, engine, route);
        // A segment straight up or down the screen has no side to it, so leave the
        // facing where the last sideways one put it rather than flipping to a default.
        float aim = route.headingX(travelled);
        if (aim < -0.15f) faceLeft = true;
        else if (aim > 0.15f) faceLeft = false;
        float beat = engine.feastBeat();
        // Three goes at it rather than one: the same character move run three times
        // across the beat, each less committed than the last, so it reads as a chomp and
        // two follow-ups. Each contact is what takes the next bite out of the item.
        float chomp = -1f, strength = 1f;
        int bite = Engine.bitesTaken(beat);
        if (engine.atPrize()) beat = -1f;           // the chest is a dance, not a meal
        if (beat >= 0f) {
            float eaten = Engine.eatPhase(beat);
            chomp = eaten - (float) Math.floor(eaten);
            if (eaten >= Art.BITE_COUNT || eaten < 0f) chomp = -1f;
            strength = 1f - 0.21f * Math.min(bite, Art.BITE_COUNT - 1);
        }

        int moveId = Anim.feastMoveId(theme.feastKind);

        // Running out of time: the buddy starts to sweat. The HURRY state is already
        // faster and more frantic; this is the part a five-year-old reads instantly.
        if (engine.isLowTime() && !engine.isPaused() && sweatTimer >= SWEAT_SECONDS) {
            sweatTimer = 0f;
            view.particles.emote(Art.GLYPH_DROP, 2, x + height * 0.16f,
                                 feet - height * 0.78f, height * 0.13f, 0xFF7FC6F0,
                                 130f, height * 0.10f, 0.35f);
        }

        // A poke. There is one action slot on the buddy, and while it is eating the
        // chomp owns it -- so a poke mid-meal is layered outside instead, as a hop and a
        // grow about the feet, which composes with whatever the chomp is doing. Poked
        // while just walking, it runs its SIGNATURE move -- its party piece, not the
        // move it makes at food, which is what it used to run and is the wrong answer
        // to being touched.
        float poke = pokeRemaining <= 0f ? -1f : 1f - pokeRemaining / POKE_SECONDS;
        if (poke >= 0f) {
            if (chomp < 0f) {
                moveId = Anim.signatureMoveId(theme.signatureKind);
                chomp = poke;
                // Keep poking and it commits harder. The first two are a shrug; the
                // third is the whole party piece.
                strength = pokeStreak >= 3 ? 1f : 0.62f;
                // The trike lands its stomp hard enough to raise dust. Edge-detected
                // rather than emitted every frame of the impact, the same way the
                // pickup burst below is.
                if (theme.signatureKind == Anim.SIG_STOMP && !stompPuffed && poke > 0.60f) {
                    stompPuffed = true;
                    burstPalette[0] = 0xFFCFC3B2;
                    burstPalette[1] = 0xFFE3DACB;
                    burstPalette[2] = 0xFFBFB3A2;
                    burstPalette[3] = 0xFFF0E9DD;
                    view.particles.burst(9, x, feet, -90f, 168f, 70f, 240f, burstPalette);
                }
            } else {
                float hop = (float) Math.sin(poke * (float) Math.PI);
                feet -= height * 0.11f * hop;
                height *= 1f + 0.07f * hop;
            }
        }

        // No contact shadow under a character that hovers. It was harmless while the
        // walk was a line along the ground; on a route that climbs the whole scene a bee
        // three quarters of the way up the sky was trailing a grey smudge through the
        // air beneath it.
        view.drawBuddy(c, theme.index, x, feet, height, theme.temperament.hover <= 0f,
                       moveId, chomp, strength, faceLeft);

        // The reaction runs off the same beat as the motion, so the word lands with the
        // bite rather than on a fraction of a segment that stretches with the timer.
        if (beat >= 0f && beat < 0.72f) {
            float bubbleW = Layout.W * 0.24f;
            float bubbleH = bubbleW * 0.42f;
            scratch.set(x + height * 0.22f, feet - height - bubbleH * 0.4f,
                        x + height * 0.22f + bubbleW, feet - height + bubbleH * 0.6f);
            if (scratch.right > Layout.W - 20f) scratch.offset(Layout.W - 20f - scratch.right, 0f);
            if (scratch.left < 20f) scratch.offset(20f - scratch.left, 0f);
            Theme.card(c, scratch, scratch.height() * 0.42f, 0xF7FFFFFF);
            Theme.fitText(c, theme.munchWord, scratch, Theme.T2, 14f,
                          theme.ink, Paint.Align.CENTER, true);
        }
    }

    /**
     * A tap in the walking lane, which counts only if it landed on the buddy.
     *
     * <p>{@code onPressDown} is the one place a screen sees raw coordinates, and it fires
     * on ACTION_DOWN, which is what makes this possible at all: the buddy's x is a
     * function of the clock and no registered region can follow it. Returning true claims
     * the gesture, so the lane does not also fire {@link #onRegion} on release -- there
     * is nothing else in the lane to tap and no scrolling on this screen, so nothing is
     * lost by taking it.
     */
    @Override boolean onPressDown(int id, int data, float x, float y) {
        if (id != R_ROUTE) return false;
        // Not through the paused veil: it says "Tap play to carry on", and a buddy
        // barking from behind it is answering a different question.
        if (!view.engine.isPaused()
                && onBuddy(view.layout, view.engine, route, x, y)) {
            pokeStreak = pokeIdle > POKE_STREAK_RESET ? 1 : pokeStreak + 1;
            pokeIdle = 0f;
            pokeRemaining = POKE_SECONDS;
            stompPuffed = false;
            playPokeSound();
            emitPokeEmote();
            view.startClock();
        }
        return true;                         // in the lane either way; see above
    }

    /**
     * The poke answers differently each time, which is what makes a child do it again.
     *
     * <p>First the character's own voice, then a giggle, then the whoosh of it actually
     * spinning. Poked mid-meal it squeaks instead: its mouth is full, and the same hello
     * it gives on the picker screen would be the wrong sound coming out of it.
     */
    private void playPokeSound() {
        int buddy = view.buddy().index;
        if (view.engine.feastBeat() >= 0f) {
            view.activity.playPoke(Sounds.POKE_SQUEAK, buddy);
        } else if (pokeStreak >= 3) {
            view.activity.playPoke(Sounds.POKE_SPIN, buddy);
        } else if (pokeStreak == 2) {
            view.activity.playPoke(Sounds.POKE_GIGGLE, buddy);
        } else {
            view.activity.playBuddySound(buddy);
        }
    }

    /**
     * Hearts for a poke, and stars once the child has worked out that it keeps going.
     *
     * <p>Two or three pieces, never more: a dozen hearts off one tap reads as a bug
     * rather than as affection.
     */
    private void emitPokeEmote() {
        Layout layout = view.layout;
        float height = bodyHeight(layout, view.engine, route);
        float hx = walkX(layout, view.engine, route);
        float hy = walkY(layout, view.engine, route) - height * 0.92f;
        if (pokeStreak >= 3) {
            view.particles.emote(Art.GLYPH_STAR, 5, hx, hy, height * 0.16f,
                                 Theme.GOLD, 260f, height * 0.22f, -0.15f);
        } else {
            // The cheek blush, which is the one colour all eight characters share, so a
            // heart reads as coming off the buddy whichever one it is.
            view.particles.emote(Art.GLYPH_HEART, 3, hx, hy, height * 0.15f,
                                 BuddyTheme.CHEEK, 200f, height * 0.16f, -0.1f);
        }
    }

    /**
     * The area the buddy moves through, which is the region the route registers.
     *
     * <p>Has to contain every point {@link #onBuddy} would accept, at every screen size
     * and every point along the route -- a tap outside it never reaches
     * {@link #onPressDown} at all, so the buddy would simply stop responding somewhere
     * along the way with nothing to show for it. tools/SelfTest.java proves the
     * containment rather than leaving it to two sets of margins agreeing by eye.
     *
     * <p>Derived from where the buddy can actually be rather than guessed, and derived
     * TIGHTLY on purpose. Its feet are on a waypoint, so they never come nearer the edge
     * of the region than half a cell less the nudge off centre {@link Route#JITTER}
     * allows; it stands up to a mouth-length back along the heading; a poke lands
     * {@link #POKE_REACH} either side of that, and reaches from a body above its feet to
     * a tenth of one below. A buddy on a back row is drawn smaller, so working from the
     * front-row height is the conservative way round.
     *
     * <p>Tight because a loose bound is a gate that cannot fail: written as the region
     * plus a generous margin, this contained everything the poke accepts with half a cell
     * to spare, and tools/SelfTest.java could not tell a correct bound from one shrunk by
     * a tenth. It can now.
     */
    static void routeBounds(Layout layout, Route route, RectF out) {
        float height = buddyHeight(layout, route);
        RectF region = route.region;
        float reach = height * (MOUTH_AHEAD + POKE_REACH);
        float inset = 0.5f - Route.JITTER;
        float halfW = route.cellW() * inset, halfH = route.cellH() * inset;
        out.set(region.left + halfW - reach, region.top + halfH - height * 1.12f,
                region.right - halfW + reach, region.bottom - halfH + height * 0.12f);
    }

    /**
     * Is the point on the buddy, wherever along the route it has got to?
     *
     * <p>Its own method, and package-visible, so tools/SelfTest.java can prove it against
     * a real Layout and a clock-driven Engine at every screen size. A hit test that only
     * exists inline in a touch handler is one nothing off the device can reach.
     *
     * <p>Generous horizontally: the sprite is about that wide, and the idle sway is
     * +-1.6% of the scene either side, which is not worth tracking here.
     */
    static boolean onBuddy(Layout layout, Engine engine, Route route, float x, float y) {
        float height = bodyHeight(layout, engine, route);
        float bx = walkX(layout, engine, route);
        float by = walkY(layout, engine, route) - height * 0.5f;
        return Math.abs(x - bx) <= height * POKE_REACH
            && Math.abs(y - by) <= height * 0.60f;
    }

    // ------------------------------------------------------------------- the route
    //
    // {@link Route} owns the grid, the winding path through it and the depth of each row.
    // What lives here is the pixel side: how big a drop is, how big the buddy is, and
    // where the buddy stands relative to the mouth the route is drawn through.
    //
    // Two builds ago the drops were placed relative to the buddy, so the line of them
    // slid along as it walked and it never travelled past anything. Then they got world
    // positions and a camera scrolled six of them at a time past a lane at the bottom of
    // the scene, which fixed the travelling but left the sky above empty and made every
    // morning the same walk. All of them are on screen at once now -- so the camera, and
    // the lane spacing and world coordinates it needed, have gone with it.

    /**
     * The drawn size of one collectible at the front of the field.
     *
     * <p>From the CELL, so a dense morning packs smaller drops and a sparse one gets big
     * ones. The old cap still binds at the sparse end: a three-drop morning has cells
     * most of the scene across, and a treat that size would dwarf the buddy.
     *
     * <p>The two allowances differ on purpose. Sideways a drop keeps clear of its
     * neighbour, because two treats touching read as one shape. Vertically it may be
     * nearly half again the row pitch and overlap the row behind, because that is what a
     * field receding into the distance looks like -- and holding it to the pitch instead
     * is what made a walker's shallow band produce treats too small to make out.
     */
    static float itemSize(Layout layout, Route route) {
        return Math.min(Math.min(route.cellW() * 0.80f, route.cellH() * 1.35f),
                        Math.min(layout.advScene.height() * 0.20f, Layout.W * 0.19f));
    }

    /**
     * How tall the buddy is drawn at the front of the field.
     *
     * <p>Tied to the BAND rather than to the row pitch, for the same reason the drop
     * size is: rows overlap, so a buddy three shallow rows tall is not too big for the
     * field, it is standing in it. Tied to the row pitch instead, a walker on a band a
     * quarter of the scene deep came out under two hundred units and read as a toy.
     *
     * <p>And tied to the room around the outermost cells, because nothing clips the
     * scene. A flyer standing on the back row of a short screen at its full height drew
     * its head straight over the tally chip, and one on the last column ran off the right
     * edge with half a wing missing. Both are measured to where its feet can actually
     * get -- the outermost cell centre, plus the nudge {@link Route#JITTER} allows --
     * rather than to the edge of the band, and sideways the room also has to cover the
     * mouth-length lean and how far the artwork reaches from its own centre.
     */
    static float buddyHeight(Layout layout, Route route) {
        float inset = 0.5f - Route.JITTER;
        float headroom = route.region.top + route.cellH() * inset - layout.advScene.top;
        float sideroom = layout.advScene.right - route.region.right + route.cellW() * inset;
        return Math.min(Math.min(Math.min(layout.advScene.height() * 0.46f, Layout.W * 0.42f),
                                 route.region.height() * 0.85f),
                        Math.min(headroom, sideroom / (MOUTH_AHEAD + Route.SPRITE_REACH)));
    }

    /** How far along the route the morning has got, in whole-and-fraction segments. */
    static float travelled(Engine engine) {
        int total = engine.collectibleCount();
        float s = engine.progress() * total;
        float stop = total - CHEST_STANDOFF;
        return s > stop ? stop : s;
    }

    /** Where the treat on waypoint {@code index} floats: over the spot, at mouth height. */
    static float itemY(Layout layout, Route route, int index) {
        return route.y(index) - buddyHeight(layout, route) * MOUTH_UP * route.scaleAt(index);
    }

    /**
     * Where the buddy's mouth is.
     *
     * <p>This is the point the contract is written on: it lands exactly on drop
     * {@code i} at the instant the engine counts drop {@code i} collected, which is what
     * ties what the tally says to what the screen shows. tools/SelfTest.java asserts it
     * in both axes.
     */
    static float mouthX(Engine engine, Route route) {
        return route.travelX(travelled(engine));
    }

    static float mouthY(Layout layout, Engine engine, Route route) {
        return route.travelY(travelled(engine))
             - bodyHeight(layout, engine, route) * MOUTH_UP;
    }

    /** How tall the buddy is right now, with the depth of the row it is crossing. */
    static float bodyHeight(Layout layout, Engine engine, Route route) {
        return buddyHeight(layout, route) * route.travelScale(travelled(engine));
    }

    /**
     * Where the buddy stands, horizontally.
     *
     * <p>A mouth-length back along the route from its mouth -- horizontally only, because
     * these characters are drawn side-on and a mouth cannot point up the screen. On a
     * segment that runs straight up or down the heading has no sideways part, so the
     * buddy stands directly under the treat and reaches up, which is right.
     *
     * <p>Package-visible because tools/SelfTest.java needs to tap it; it used to
     * recompute the formula itself, and when the lane got a camera the copy in the test
     * went on pointing at where the buddy no longer was.
     */
    static float walkX(Layout layout, Engine engine, Route route) {
        float s = travelled(engine);
        return route.travelX(s)
             - route.headingX(s) * bodyHeight(layout, engine, route) * MOUTH_AHEAD;
    }

    /** Where the buddy's FEET are: on the waypoint, which is a place to stand. */
    static float walkY(Layout layout, Engine engine, Route route) {
        return route.travelY(travelled(engine));
    }

    /**
     * The treats laid out across the field.
     *
     * <p>Each sits on its own waypoint, so they stand still and the buddy threads between
     * them. Two passes: everything further up the screen than the buddy's mouth is
     * further away and goes behind it, everything level or nearer goes in front, and the
     * one being eaten is always in front because the bites come out of the side the buddy
     * is standing on.
     *
     * <p>Which one is being eaten is the NEAREST waypoint -- {@code round} rather than
     * the collected count. The feast starts {@link Engine#FEAST_LEAD} before the pickup
     * and runs for {@link Engine#FEAST_SECONDS}, so for the last three quarters of it the
     * count has already moved on; keying off the count put the bites on the treat AHEAD
     * of the buddy for most of every meal, and left the one it had just eaten sitting
     * there whole. On one line of drops behind a sprite two hundred units wide that was
     * invisible. On a field of them it would not be.
     */
    private void drawTrail(Canvas c, Layout layout, BuddyTheme theme, Engine engine,
                           float t, boolean front) {
        int total = engine.collectibleCount();
        if (total <= 0) return;

        float size = itemSize(layout, route);
        float beat = engine.feastBeat();
        float travelled = travelled(engine);
        int eating = beat >= 0f ? Math.round(travelled) : -1;
        // Everything before the one in its mouth has been eaten. Between meals that is
        // everything up to and including the last one collected.
        int first = eating >= 0 ? eating : engine.collectedCount() + 1;
        float mouthY = mouthY(layout, engine, route);

        for (int index = Math.max(1, first); index < total; index++) {
            boolean chewing = index == eating;
            if ((chewing || itemY(layout, route, index) >= mouthY - 0.5f) != front) continue;

            float drawn = size * route.scaleAt(index);
            float x = route.x(index);
            float y = itemY(layout, route, index)
                    + (float) Math.sin(t * 1.6f + index) * drawn * 0.06f;
            if (chewing) {
                // Whole, then a bite gone, then two, then nothing. Each bite pops as it
                // lands, which is what makes it read as a bite rather than the treat
                // quietly changing shape.
                int bites = Engine.bitesTaken(beat);
                if (bites >= Art.BITE_COUNT) continue;
                float eaten = Engine.eatPhase(beat);
                float pop = Math.max(0f, 1f - Math.abs(eaten - bites) * 6f);
                Icons.collectible(c, theme.index, x, y, drawn, true, pop, false, bites);
            } else {
                Icons.collectible(c, theme.index, x, y, drawn, true, 0f, false);
            }
        }

        if (front) {
            int collected = engine.collectedCount();
            int at = Math.max(1, Math.min(collected, total - 1));
            fireBurst(layout, theme, engine, collected,
                      route.x(at), itemY(layout, route, at));
        }
    }

    /**
     * The way to the chest, dotted in between the treats.
     *
     * <p>Without it a field of drops is a field, not a route: nothing says which one
     * comes next or where the winding ends up. Faint on purpose -- it is the floor of the
     * picture, and the drops and the buddy are what should be read. The part already
     * walked is fainter still, which is the only progress cue on the scene itself.
     */
    private void drawDots(Canvas c, Layout layout, BuddyTheme theme, Engine engine) {
        int last = route.count() - 1;
        if (last < 1) return;
        Paint fill = Theme.FILL;
        fill.setShader(null);
        float travelled = travelled(engine);
        float dot = Math.min(route.cellW(), route.cellH()) * 0.07f;

        for (int seg = 0; seg < last; seg++) {
            float x0 = route.x(seg), y0 = itemY(layout, route, seg);
            float x1 = route.x(seg + 1), y1 = itemY(layout, route, seg + 1);
            float s0 = route.scaleAt(seg), s1 = route.scaleAt(seg + 1);
            fill.setColor(Theme.alpha(theme.light, seg + 1 <= travelled ? 46 : 110));
            for (int k = 1; k <= TRAIL_DOTS; k++) {
                float f = k / (float) (TRAIL_DOTS + 1);
                c.drawCircle(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f,
                             dot * (s0 + (s1 - s0) * f), fill);
            }
        }
    }

    /** One confetti burst per item reached, at the item, in the buddy's own colours. */
    private void fireBurst(Layout layout, BuddyTheme theme, Engine engine,
                           int collected, float x, float y) {
        if (collected < burstedThrough) burstedThrough = collected;   // a new run
        if (collected <= burstedThrough || engine.allDone()) return;
        burstedThrough = collected;
        burstPalette[0] = theme.primary;
        burstPalette[1] = theme.accent;
        burstPalette[2] = theme.accent2;
        burstPalette[3] = 0xFFFFFFFF;
        view.particles.burst(14, x, y, -90f, 150f,
                             layout.advScene.height() * 0.25f,
                             layout.advScene.height() * 0.55f, burstPalette);
    }

    /**
     * The treasure chest at the end of the lane.
     *
     * <p>One chest for all eight characters, where each used to have its own goal -- a
     * basket, a hive, a volcano. A padlocked chest that springs open is a far better fit
     * for the lock than a padlocked volcano was, and it makes the last collectible and
     * the destination the same object instead of two things competing for the finish.
     */
    private void drawGoal(Canvas c, Layout layout, BuddyTheme theme, Engine engine) {
        RectF box = layout.advGoal;
        int at = route.count() - 1;
        // On the route like everything else, at its last waypoint -- which is somewhere
        // new every morning. advGoal no longer says WHERE the chest is, only how big it
        // may be; the cell caps it too, so a chest on a crowded grid cannot spill over
        // the treats beside it.
        float size = Math.min(Math.min(box.width(), box.height()),
                              Math.min(route.cellW(), route.cellH()) * 1.35f)
                   * route.scaleAt(at);
        boolean open = engine.atPrize();
        float cx = route.x(at);
        float cy = itemY(layout, route, at);

        // 0 while the padlock holds, then it runs through the opening frames once and
        // stays open. prizeRemaining counts DOWN from PRIZE_SECONDS, so this counts up.
        float turn = !open ? 0f
                   : prizeRemaining <= 0f ? 1f
                   : 1f - prizeRemaining / PRIZE_SECONDS;

        if (open) {
            // A glow behind it, the same one the goal used to carry when it opened. It
            // comes up with the lid rather than snapping on with the first frame.
            Paint fill = Theme.FILL;
            fill.setShader(null);
            float lit = Math.min(1f, turn * 2.2f);
            fill.setColor(Theme.alpha(Theme.GOLD, (int) (70 * lit)));
            c.drawCircle(cx, cy, size * 0.72f, fill);
            fill.setColor(Theme.alpha(Theme.GOLD, (int) (48 * lit)));
            c.drawCircle(cx, cy, size * 0.56f, fill);
        }
        Clay.contactShadow(c, cx, cy + size * 0.42f, size * 0.40f, size * 0.12f, 1f);
        view.drawProp(c, MorningView.chestFrame(turn), cx, cy, size, 0f, 255);
        // No padlock badge. A closed chest already reads as shut, and the disc sat over
        // the best part of the artwork.
        if (open && prizeRemaining > 0f) {
            // The star climbing out. Eased so it leaves fast and settles, and it fades
            // rather than stopping dead -- the chest keeps its own star either way.
            float p = 1f - prizeRemaining / PRIZE_SECONDS;
            float rise = (float) Math.sin(p * Math.PI * 0.5f);
            int alpha = p < 0.72f ? 255 : (int) (255 * (1f - (p - 0.72f) / 0.28f));
            view.drawProp(c, MorningView.PROP_STAR, cx,
                          cy - size * (0.06f + 0.72f * rise),
                          size * (0.30f + 0.30f * rise),
                          (float) Math.sin(p * 7f) * 12f, Math.max(0, alpha));
        }
    }

    /** Gold and the buddy's own colours, thrown up out of the chest as it opens. */
    private void firePrizeBurst(Layout layout, BuddyTheme theme, Engine engine) {
        int at = route.count() - 1;
        burstPalette[0] = Theme.GOLD;
        burstPalette[1] = 0xFFFFF06A;
        burstPalette[2] = theme.primary;
        burstPalette[3] = 0xFFFFFFFF;
        view.particles.burst(26, route.x(at),
                             itemY(layout, route, at) - layout.advGoal.height() * 0.10f,
                             -90f, 128f,
                             layout.advScene.height() * 0.45f,
                             layout.advScene.height() * 0.95f, burstPalette);
    }

    private void drawTopBar(Canvas c, Layout layout, BuddyTheme theme) {
        Icons.glyphChip(c, Art.GLYPH_CHEVRON_LEFT, layout.advBack, 0xEAFFFFFF,
                        theme.ink, view.pressOn(R_BACK));
        Icons.glyphChip(c, view.engine.isPaused() ? Art.GLYPH_PLAY : Art.GLYPH_PAUSE,
                        layout.advPause, 0xEAFFFFFF, theme.ink, view.pressOn(R_PAUSE));

        RectF title = layout.advTitle;
        float size = Math.min(title.height() * 0.44f, Layout.W * 0.062f);
        // Not white: wordmark() haloes in white, so a white fill vanished into its own
        // outline. The deep blue is Home's "Morning", which ties the two titles together.
        Theme.wordmark(c, "Buddy", title.centerX(), title.top + title.height() * 0.32f,
                       size, 0xFF1857A5);
        Theme.wordmark(c, "Adventure!", title.centerX(), title.top + title.height() * 0.78f,
                       size * 1.06f, 0xFFFFF06A);
    }

    /**
     * The quick mute, under the pause chip.
     *
     * <p>The setting itself lives in Grown-Ups, behind the PIN, and getting to it from
     * here costs five steps, two PIN entries and kid mode -- which is no use at all to a
     * parent who wants the noise to stop now. This is the same {@code "song"} preference,
     * so the two stay in step and there is no second setting to disagree with the first.
     * No PIN: turning the sound off is not leaving the app.
     */
    private void drawMute(Canvas c, Layout layout, BuddyTheme theme) {
        boolean on = view.pref("song", true);
        Icons.glyphChip(c, on ? Art.GLYPH_SPEAKER : Art.GLYPH_SPEAKER_OFF,
                        layout.advMute, on ? 0xEAFFFFFF : 0xEAE4E9EF,
                        on ? theme.ink : 0xFF7B8794, view.pressOn(R_MUTE));
    }

    private void drawClock(Canvas c, Layout layout, BuddyTheme theme, Engine engine) {
        RectF box = layout.advClock;
        Theme.card(c, box, box.height() * 0.34f, 0xF8FFFFFF);
        Theme.drawTime(c, engine.remainingMs(), box.centerX(),
                       box.centerY() - box.height() * 0.11f,
                       Math.min(Theme.D1, box.height() * 0.52f), Theme.INK, Paint.Align.CENTER);

        String caption;
        int colour;
        if (engine.isPaused()) {
            caption = "Paused";
            colour = theme.accent;
        } else if (engine.isTimeUp()) {
            caption = "Time is up. Finish your tasks!";
            colour = 0xFFD4552F;
        } else if (engine.isLowTime()) {
            caption = "Nearly there. Keep going!";
            colour = Theme.WARN;
        } else {
            caption = "Keep going!";
            colour = Theme.INK_MUTED;
        }
        Theme.textCentered(c, caption, box.centerX(), box.bottom - box.height() * 0.19f,
                           Theme.B2, colour, Paint.Align.CENTER, true);
    }

    private void drawTally(Canvas c, Layout layout, BuddyTheme theme, Engine engine) {
        RectF band = layout.advTally;
        int total = engine.collectibleCount();
        int collected = engine.collectedCount();

        String label = collected + " of " + total + " " + theme.collectibleNoun(total);
        float icon = band.height() * 0.86f;
        float textSize = Math.min(Theme.T2, band.height() * 0.44f);
        float textWidth = Theme.measure(label, textSize, true);
        float chipWidth = Math.min(band.width(), icon + 16f + textWidth + band.height() * 0.9f);

        scratch.set(band.centerX() - chipWidth * 0.5f, band.top,
                    band.centerX() + chipWidth * 0.5f, band.bottom);
        Theme.card(c, scratch, scratch.height() * 0.5f, 0xF2FFFFFF);
        Theme.gloss(c, scratch, scratch.height() * 0.5f, 0.7f);

        float startX = scratch.centerX() - (icon + 16f + textWidth) * 0.5f;
        Icons.collectible(c, theme.index, startX + icon * 0.5f, scratch.centerY(), icon,
                          collected > 0, 0f, false);
        Theme.textCentered(c, label, startX + icon + 16f, scratch.centerY(),
                           textSize, theme.ink, Paint.Align.LEFT, true);
    }

    private void drawProgress(Canvas c, Layout layout, BuddyTheme theme, Engine engine) {
        RectF track = layout.advProgress;
        Paint fill = Theme.FILL;
        fill.setShader(null);
        float radius = track.height() * 0.5f;
        fill.setColor(0x59FFFFFF);
        c.drawRoundRect(track, radius, radius, fill);

        float fraction = engine.collectibleCount() == 0 ? 0f
                       : engine.collectedCount() / (float) engine.collectibleCount();
        if (fraction > 0f) {
            scratch.set(track.left, track.top,
                        track.left + Math.max(track.height(), track.width() * fraction),
                        track.bottom);
            fill.setColor(theme.primary);
            c.drawRoundRect(scratch, radius, radius, fill);
        }

        // The count itself lives in the tally chip at the top; repeating it here just
        // put two copies of the same number on one screen.
    }

    private void drawTaskCard(Canvas c, Layout layout, BuddyTheme theme, Engine engine) {
        RectF box = layout.advTaskCard;
        Theme.card(c, box, box.height() * 0.26f, 0xF8FFFFFF);
        if (engine.taskCount() == 0) {
            Theme.fitText(c, "Add some tasks in Grown-Ups", box, Theme.T2, 16f,
                          Theme.INK_MUTED, Paint.Align.CENTER, true);
            return;
        }

        float pad = box.height() * 0.16f;
        float iconSize = box.height() - pad * 2f;
        int index = Math.min(engine.activeIndex(), engine.taskCount() - 1);
        Icons.activityChip(c, Art.activityKind(engine.taskKey(index)), theme,
                           box.left + pad + iconSize * 0.5f, box.centerY(), iconSize, false);

        float textLeft = box.left + pad * 2f + iconSize;
        // Was a hard Layout.W * 0.11f, which never tracked the counter's type size at
        // all. Measure the string the counter will actually draw, as the Grown-Ups rows
        // already do, so the name box stops exactly where the counter starts.
        String counter = (index + 1) + " of " + engine.taskCount();
        float counterSize = Theme.rowSubtitleSize(box);
        float counterWidth = Theme.measure(counter, counterSize, true) + pad;
        scratch.set(textLeft, box.top + pad * 0.5f,
                    box.right - pad - counterWidth, box.centerY() + box.height() * 0.04f);
        Theme.fitText(c, engine.taskName(index), scratch, Theme.rowTitleSize(box), 22f,
                      Theme.INK, Paint.Align.LEFT, true);
        scratch.set(textLeft, box.centerY() + box.height() * 0.06f,
                    box.right - pad - counterWidth, box.bottom - pad * 0.5f);
        Theme.fitText(c, Art.ACTIVITY_SUBTITLES[Art.activityKind(engine.taskKey(index))],
                      scratch, Theme.rowSubtitleSize(box), 16f,
                      Theme.INK_MUTED, Paint.Align.LEFT, false);

        Theme.textCentered(c, counter, box.right - pad, box.centerY(), counterSize,
                           theme.ink, Paint.Align.RIGHT, true);
    }

    private void drawAction(Canvas c, Layout layout, Engine engine) {
        RectF box = layout.advAction;
        float press = view.pressOn(R_ACTION);
        boolean done = engine.allDone();
        Theme.button(c, box, box.height() * 0.5f,
                     done ? Theme.SUCCESS_DEEP : Theme.SUCCESS,
                     Theme.darken(done ? Theme.SUCCESS_DEEP : Theme.SUCCESS, 0.22f),
                     press, 1f);
        float cy = box.centerY() + box.height() * 0.04f * press;
        String label = done ? "YOU DID IT!" : "I DID IT!";
        float glyph = box.height() * 0.40f;
        float room = Theme.labelRoom(box, glyph + 20f);
        float textSize = Theme.labelSize(label, Theme.buttonLabelSize(box), 16f, room);
        float textWidth = Theme.measureLabel(label, textSize);
        float startX = box.centerX() - (glyph + 20f + textWidth) * 0.5f;
        Icons.glyph(c, done ? Art.GLYPH_STAR : Art.GLYPH_CHECK,
                    startX + glyph * 0.5f, cy, glyph, 0xFFFFFFFF);
        Theme.label(c, label, startX + glyph + 20f, cy,
                    textSize, 0xFFFFFFFF, Paint.Align.LEFT);
    }

    private void drawPausedVeil(Canvas c, Layout layout) {
        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setColor(0x7A0E3560);
        c.drawRect(layout.advScene, fill);
        Theme.label(c, "Tap play to carry on", layout.advScene.centerX(),
                    layout.advScene.centerY(), Theme.H2, 0xFFFFFFFF,
                    Paint.Align.CENTER);
    }

    @Override int tapSound(int id, int data) {
        // The route never gets here -- onPressDown claims it -- but say so anyway,
        // since a poke already answers with the buddy's own voice.
        if (id == R_ROUTE) return -1;
        return id == R_ACTION ? Sounds.UI_CONFIRM : Sounds.UI_TAP;
    }

    @Override void onRegion(int id, int data) {
        switch (id) {
            case R_BACK:
                // Leaving the adventure is a parent action: it is the same gate as
                // unlocking kid mode, never a plain back.
                view.activity.requestParentUnlock();
                break;
            case R_PAUSE:
                if (view.engine.isPaused()) view.engine.resume();
                else view.engine.pause();
                view.startClock();
                break;
            case R_MUTE:
                view.setPref("song", !view.pref("song", true));
                break;
            case R_ROUTE:
                // Claimed in onPressDown, which returns true, so this never runs. It is
                // here because the region gate requires every registered region to be
                // handled, and that is the right rule: a region nothing answers is
                // normally a control that has quietly stopped working.
                break;
            case R_ACTION:
                if (!view.engine.allDone()) {
                    view.engine.completeActive();
                } else {
                    view.route(MorningView.SCREEN_COMPLETE);
                }
                break;
            default:
                break;
        }
    }

    @Override boolean onBack() {
        view.activity.requestParentUnlock();
        return true;
    }
}
