package com.morningmission.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The countdown: the buddy travels through its world collecting things on the way to a
 * goal, while the child works through the routine.
 */
final class ScreenAdventure extends Screen {

    private static final int R_BACK = 1, R_PAUSE = 2, R_ACTION = 3, R_MUTE = 4, R_LANE = 5;

    private final RectF scratch = new RectF();

    /**
     * How far along the lane the buddy sits before the view starts following it.
     *
     * <p>A fraction of the lane's width. Below it the buddy walks out from the left edge
     * with the whole lane ahead; past it the buddy holds station and the lane slides by.
     */
    private static final float CAMERA_ANCHOR = 0.28f;

    /**
     * How far in front of the buddy's centre its mouth is, as a fraction of its height.
     *
     * <p>A treat has to land at the muzzle, not behind a sprite half the screen wide --
     * drawn on the buddy's own centre the bites, which are the whole point of them, were
     * never visible. Burger Buddy is already holding a burger of its own.
     */
    private static final float MOUTH_AHEAD = 0.30f;

    /** How far either side of the buddy a tap still counts as a poke, in body heights. */
    private static final float POKE_REACH = 0.45f;

    /** How many treats a long morning shows on the lane at once. */
    private static final int VISIBLE_DROPS = 6;

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
    }

    @Override void layout(Layout layout, HitMap hits) {
        // The lane goes down FIRST. The buddy walks across it continuously and the hit
        // map is rebuilt only on entry, resize, scroll and task completion -- never per
        // frame -- so a region cannot follow the buddy. This is the whole strip it walks
        // through; onPressDown does the distance test against where the buddy actually
        // is. Registering it first means the chips and the button, which are added after
        // and win where they overlap, keep their taps.
        laneBounds(layout, scratch);
        hits.add(R_LANE, scratch);

        hits.addPadded(R_BACK, layout.advBack, layout.minTouchUnits(), 0);
        hits.addPadded(R_PAUSE, layout.advPause, layout.minTouchUnits(), 0);
        hits.addPadded(R_MUTE, layout.advMute, layout.minTouchUnits(), 0);
        hits.add(R_ACTION, layout.advAction);
    }

    @Override void draw(Canvas c, Layout layout, float t, float dt) {
        BuddyTheme theme = view.buddy();
        Engine engine = view.engine;
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
        // Treats still ahead go behind the buddy; the one in its mouth goes in front,
        // because the bites come out of the side the buddy is standing on.
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
     * The buddy walks the length of the trail as the morning passes.
     *
     * <p>The old build parked it at a fixed x while the goal sat at the far right, so it
     * never actually approached the thing it was supposed to be travelling toward --
     * despite the README describing exactly that.
     */
    private void drawBuddy(Canvas c, Layout layout, BuddyTheme theme, Engine engine, float t) {
        RectF trail = layout.advTrail;
        float x = walkX(layout, engine)
                + (float) Math.sin(t * 1.5f) * layout.advScene.width() * 0.016f;
        float feet = trail.centerY();
        float height = buddyHeight(layout);
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

        view.drawBuddy(c, theme.index, x, feet, height, true, moveId, chomp, strength);

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
        if (id != R_LANE) return false;
        // Not through the paused veil: it says "Tap play to carry on", and a buddy
        // barking from behind it is answering a different question.
        if (!view.engine.isPaused() && onBuddy(view.layout, view.engine, x, y)) {
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
        float height = buddyHeight(layout);
        float hx = walkX(layout, view.engine);
        float hy = layout.advTrail.centerY() - height * 0.92f;
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
     * The strip the buddy walks through, which is the region the lane registers.
     *
     * <p>Has to contain every point {@link #onBuddy} would accept, at every screen size
     * and every point along the walk -- a tap outside it never reaches
     * {@link #onPressDown} at all, so the buddy would simply stop responding somewhere
     * along the trail with nothing to show for it. tools/SelfTest.java proves the
     * containment rather than leaving it to two sets of margins agreeing by eye.
     */
    static void laneBounds(Layout layout, RectF out) {
        float height = buddyHeight(layout);
        RectF trail = layout.advTrail;
        // Derived from the poke's own reach rather than guessed, because the two have to
        // agree: a tap onBuddy accepts but this region does not contain never arrives at
        // onPressDown at all. The buddy's centre runs from trail.left - MOUTH_AHEAD to
        // trail.right - MOUTH_AHEAD, and a poke lands POKE_REACH either side of it.
        float slack = height * (MOUTH_AHEAD + POKE_REACH);
        out.set(trail.left - slack, trail.centerY() - height * 1.15f,
                trail.right + slack, trail.bottom + height * 0.15f);
    }

    /**
     * Is the point on the buddy, wherever along the trail it has walked to?
     *
     * <p>Its own method, and package-visible, so tools/SelfTest.java can prove it against
     * a real Layout and a clock-driven Engine at every screen size. A hit test that only
     * exists inline in a touch handler is one nothing off the device can reach.
     *
     * <p>Generous horizontally: the sprite is about that wide, and the idle sway is
     * +-1.6% of the scene either side, which is not worth tracking here.
     */
    static boolean onBuddy(Layout layout, Engine engine, float x, float y) {
        float height = buddyHeight(layout);
        float bx = walkX(layout, engine);
        float by = layout.advTrail.centerY() - height * 0.5f;
        return Math.abs(x - bx) <= height * POKE_REACH
            && Math.abs(y - by) <= height * 0.60f;
    }

    /** Where along the trail the buddy has walked to, without its idle bob. */
    // ------------------------------------------------------------------- the lane
    //
    // The lane is a WORLD, not a strip of screen. Every collectible has a fixed position
    // along it, the buddy travels past them, and one camera turns world into screen.
    //
    // It used to be the other way round: the collectibles were placed relative to the
    // buddy, a fixed few units ahead of it, so the whole line of them slid along as the
    // buddy walked and it never actually travelled past anything. On a sixty-minute
    // morning the tally said twenty-three and the lane showed two, because two of the
    // four it drew were behind a sprite two hundred units wide.

    /**
     * The drawn size of one collectible.
     *
     * <p>Sized from the LANE rather than from the scene, because what matters is how many
     * of them are on screen at once. At the old size -- a fifth of a scene that is 1304
     * units tall, so 205 wide -- only two and a half fitted the 724-unit lane, which is
     * how a morning with twenty-three of them came to show two.
     *
     * <p>A morning with fewer treats than {@link #VISIBLE_DROPS} sizes for the number it
     * actually has, so three of them are three good big treats rather than three small
     * ones with a lot of grass between.
     */
    static float itemSize(Layout layout, Engine engine) {
        int visible = Math.min(Math.max(1, engine.collectibleCount()), VISIBLE_DROPS);
        float fit = layout.advTrail.width() / visible / 1.35f;
        return Math.min(fit, Math.min(layout.advScene.height() * 0.20f, Layout.W * 0.19f));
    }

    /**
     * The gap between neighbouring collectibles, in world units.
     *
     * <p>The {@code max} is what makes one rule serve both ends of the range. A long
     * morning holds its spacing and the lane runs off the side of the screen to be
     * scrolled; a short one stretches until its handful of treats fill the lane, so
     * three of them are a walk rather than a huddle by the left edge. Neither is a
     * special case.
     */
    static float laneSpacing(Layout layout, Engine engine) {
        int total = Math.max(1, engine.collectibleCount());
        return Math.max(itemSize(layout, engine) * 1.35f, layout.advTrail.width() / total);
    }

    /** Where collectible {@code index} (1-based) sits along the lane. */
    static float itemWorldX(Layout layout, Engine engine, int index) {
        return index * laneSpacing(layout, engine);
    }

    /**
     * Where the chest sits: past the last treat, far enough that when the lane has
     * finished scrolling it comes to rest exactly on {@code advGoal} -- which is where
     * the prize burst and the climbing star are aimed.
     */
    static float chestWorldX(Layout layout, Engine engine) {
        return engine.collectibleCount() * laneSpacing(layout, engine)
             + (layout.advGoal.centerX() - layout.advTrail.right);
    }

    /**
     * How far along the lane the buddy's MOUTH has travelled.
     *
     * <p>The mouth rather than the middle, because the mouth is what has to arrive at a
     * treat. {@code progress() * total} is the collectible number the engine is on, so
     * multiplying by the spacing lands the mouth exactly on treat {@code i} at the moment
     * the engine counts it collected. That equality is the contract between what the
     * tally says and what the screen shows, and tools/SelfTest.java asserts it.
     */
    static float mouthWorldX(Layout layout, Engine engine) {
        return engine.progress() * engine.collectibleCount() * laneSpacing(layout, engine);
    }

    /** How far the lane has slid, in world units. */
    static float camera(Layout layout, Engine engine) {
        RectF trail = layout.advTrail;
        float span = engine.collectibleCount() * laneSpacing(layout, engine);
        float furthest = span - trail.width();
        // A lane that fits, or misses fitting by less than a pixel, does not scroll at
        // all. Without the rounding a five-drop morning on a small screen -- where the
        // spacing stretches to exactly the lane width -- crept by a fraction of a unit
        // for the whole morning, which is not a scroll, just noise.
        if (furthest < 1f) furthest = 0f;
        // Clamped by hand rather than through Theme.clamp: touching Theme runs its class
        // initialiser, which builds a Typeface, which is native-backed and cannot be
        // reached off-device -- and tools/SelfTest.java has to be able to call this.
        float want = mouthWorldX(layout, engine) - trail.width() * CAMERA_ANCHOR;
        return want < 0f ? 0f : (want > furthest ? furthest : want);
    }

    /** A point on the lane, in screen units. */
    static float laneScreenX(Layout layout, Engine engine, float worldX) {
        return layout.advTrail.left + worldX - camera(layout, engine);
    }

    /**
     * Where the buddy is on screen.
     *
     * <p>Package-visible because tools/SelfTest.java needs to tap it. It used to
     * recompute the formula itself, and when the lane became a world the copy in the
     * test went on pointing at where the buddy no longer was.
     */
    static float walkX(Layout layout, Engine engine) {
        return laneScreenX(layout, engine, mouthWorldX(layout, engine))
             - buddyHeight(layout) * MOUTH_AHEAD;
    }

    private static float buddyHeight(Layout layout) {
        return Math.min(layout.advScene.height() * 0.46f, Layout.W * 0.42f);
    }

    /**
     * The collectibles lying on the trail ahead of the buddy.
     *
     * <p>These used to be a strip of thumbnails at the top of the screen, one per item,
     * which on a long morning shrank to about sixty units across -- too small to make
     * out, and nowhere near the buddy, so nothing ever appeared to be collected. They
     * now sit on the ground the buddy is walking along, at a size that reads, and the
     * buddy walks into each one and performs its own move as it arrives.
     *
     * <p>Positions are relative to the buddy, not spread across the trail: item
     * {@code collected + k} sits {@code k - fraction} spacings ahead, so the whole line
     * slides left by exactly one spacing over each segment and the next item arrives
     * under the buddy at the moment the engine counts it as collected.
     */
    /**
     * The treats lying along the lane.
     *
     * <p>Drawn at their own world positions, so they stand still and the buddy walks up
     * to each one. Two passes: everything still ahead goes behind the buddy, and the one
     * currently being eaten goes in front of it, because the bites come out of the side
     * the buddy is standing on.
     */
    private void drawTrail(Canvas c, Layout layout, BuddyTheme theme, Engine engine,
                           float t, boolean inMouth) {
        int total = engine.collectibleCount();
        if (total <= 0) return;
        int collected = engine.collectedCount();

        float size = itemSize(layout, engine);
        float height = buddyHeight(layout);
        // Held at about the height the buddy's hands are, so reaching one is a lean
        // rather than a squat -- at ankle height no amount of tilt looked like eating.
        float ground = layout.advTrail.centerY() - height * 0.38f;
        float beat = engine.feastBeat();
        int eating = beat >= 0f ? collected + 1 : -1;

        float left = -size, right = layout.advTrail.right + size * 0.35f;
        // The last one is the chest at the end of the lane, drawn by drawGoal. It counts
        // as a collectible -- the tally says "23 of 23" -- but it is never a treat lying
        // on the ground and it is never eaten.
        for (int index = Math.max(1, collected); index < total; index++) {
            boolean chewing = index == eating;
            if (chewing != inMouth) continue;
            float x = laneScreenX(layout, engine, itemWorldX(layout, engine, index));
            if (x < left || x > right) continue;

            float y = ground + (float) Math.sin(t * 1.6f + index) * size * 0.06f;
            if (chewing) {
                // Whole, then a bite gone, then two, then nothing. Each bite pops as it
                // lands, which is what makes it read as a bite rather than the treat
                // quietly changing shape.
                int bites = Engine.bitesTaken(beat);
                if (bites >= Art.BITE_COUNT) continue;
                float eaten = Engine.eatPhase(beat);
                float pop = Math.max(0f, 1f - Math.abs(eaten - bites) * 6f);
                Icons.collectible(c, theme.index, x, y, size, true, pop, false, bites);
            } else {
                Icons.collectible(c, theme.index, x, y, size, true, 0f, false);
            }
        }

        if (inMouth) {
            fireBurst(layout, theme, engine, collected,
                      laneScreenX(layout, engine,
                                  itemWorldX(layout, engine, Math.max(1, collected))),
                      ground);
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
        float size = Math.min(box.width(), box.height());
        boolean open = engine.atPrize();
        // On the lane like everything else, so it slides in from the right as the
        // morning goes rather than sitting parked at the edge from the first second.
        // Its world position is chosen so the scroll runs out exactly as it reaches
        // advGoal, which is where the burst and the climbing star are aimed.
        float cx = laneScreenX(layout, engine, chestWorldX(layout, engine));
        float cy = box.centerY();

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
        RectF box = layout.advGoal;
        float cx = laneScreenX(layout, engine, chestWorldX(layout, engine));
        burstPalette[0] = Theme.GOLD;
        burstPalette[1] = 0xFFFFF06A;
        burstPalette[2] = theme.primary;
        burstPalette[3] = 0xFFFFFFFF;
        view.particles.burst(26, cx, box.centerY() - box.height() * 0.10f,
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
        // The lane never gets here -- onPressDown claims it -- but say so anyway, since
        // a poke already answers with the buddy's own voice.
        if (id == R_LANE) return -1;
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
            case R_LANE:
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
