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
     * How many upcoming collectibles are laid out ahead of the buddy.
     *
     * <p>They are placed relative to the buddy rather than spread over the whole trail,
     * so the spacing -- and therefore the size -- stays the same whether the morning has
     * three things to find or eighteen. A longer morning gives you more of them, not
     * smaller ones.
     */
    private static final int LOOKAHEAD = 4;

    /**
     * Seconds a poke lasts. Long enough for the buddy to complete its own move, short
     * enough that a child jabbing at the screen gets a reaction to each jab.
     */
    private static final float POKE_SECONDS = 0.55f;

    /** Seconds left of the current poke, counted down in {@link #draw}. */
    private float pokeRemaining;

    /** Fires the pickup burst once per item rather than on every frame of the window. */
    private int burstedThrough = 0;
    private final int[] burstPalette = new int[4];

    ScreenAdventure(MorningView view) {
        super(view);
    }

    @Override void onEnter() {
        burstedThrough = view.engine.collectedCount();
        pokeRemaining = 0f;
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

        view.scene.drawBackground(c, theme, t);
        // Trail first: the goal stands at the end of the lane, so items still to be
        // reached slide out from behind it rather than floating across its lid.
        drawTrail(c, layout, theme, engine, t, false);
        drawGoal(c, layout, theme, engine);
        drawBuddy(c, layout, theme, engine, t);
        // The one in the buddy's mouth goes on top of it. The bites come out of the
        // item's left side, which is the side the buddy is standing on, so drawn behind
        // it the only part ever missing was the part already hidden.
        drawTrail(c, layout, theme, engine, t, true);
        view.scene.drawForeground(c, theme, t, true);

        drawTopBar(c, layout, theme);
        drawMute(c, layout, theme);
        drawClock(c, layout, theme, engine);
        drawTally(c, layout, theme, engine);
        drawTreatBoard(c, layout, theme, engine, t);
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
        if (beat >= 0f) {
            float eaten = Engine.eatPhase(beat);
            chomp = eaten - (float) Math.floor(eaten);
            if (eaten >= Art.BITE_COUNT || eaten < 0f) chomp = -1f;
            strength = 1f - 0.21f * Math.min(bite, Art.BITE_COUNT - 1);
        }

        // A poke. There is one action slot on the buddy, and while it is eating the
        // chomp owns it -- so a poke mid-meal is layered outside instead, as a hop and a
        // grow about the feet, which composes with whatever the chomp is doing. Poked
        // while just walking, it runs its own character move at full strength, which is
        // a far bigger reaction and is the one a child gets most of the time.
        float poke = pokeRemaining <= 0f ? -1f : 1f - pokeRemaining / POKE_SECONDS;
        if (poke >= 0f) {
            if (chomp < 0f) {
                chomp = poke;
                strength = 1f;
            } else {
                float hop = (float) Math.sin(poke * (float) Math.PI);
                feet -= height * 0.11f * hop;
                height *= 1f + 0.07f * hop;
            }
        }

        view.drawBuddy(c, theme.index, x, feet, height, true, theme.feastKind,
                       chomp, strength);

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
            pokeRemaining = POKE_SECONDS;
            view.activity.playBuddySound(view.buddy().index);
            view.startClock();
        }
        return true;                         // in the lane either way; see above
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
        out.set(trail.left - height * 0.5f, trail.centerY() - height * 1.15f,
                trail.right + height * 0.5f, trail.bottom + height * 0.15f);
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
        return Math.abs(x - bx) <= height * 0.45f && Math.abs(y - by) <= height * 0.60f;
    }

    /** Where along the trail the buddy has walked to, without its idle bob. */
    private static float walkX(Layout layout, Engine engine) {
        RectF trail = layout.advTrail;
        return trail.left + trail.width() * engine.progress();
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
    private void drawTrail(Canvas c, Layout layout, BuddyTheme theme, Engine engine,
                           float t, boolean inMouth) {
        int total = engine.collectibleCount();
        if (total <= 0) return;
        int collected = engine.collectedCount();
        float fraction = engine.allDone() ? 1f : engine.collectibleFraction();

        float size = Math.min(layout.advScene.height() * 0.20f, Layout.W * 0.19f);
        float spacing = size * 1.35f;
        // Items arrive in FRONT of the buddy, not on top of it. Landing them on the
        // buddy's own x put the thing being eaten behind a sprite half the screen wide,
        // so the bites -- the whole point of them -- were never visible. Offsetting by
        // a third of the buddy's height puts it at the muzzle rather than behind the
        // sprite's own middle -- Burger Buddy is already holding a burger of its own.
        float height = buddyHeight(layout);
        float base = walkX(layout, engine) + height * 0.30f;
        // Held at about the height the buddy's hands are, so reaching one is a lean
        // rather than a squat -- at ankle height no amount of tilt looked like eating.
        float ground = layout.advTrail.centerY() - height * 0.38f;
        float beat = engine.feastBeat();

        for (int k = inMouth ? 0 : 1; k <= (inMouth ? 0 : LOOKAHEAD); k++) {
            int index = collected + k;                  // 1-based item number
            if (index < 1 || index > total) continue;
            // The item being eaten stays at the buddy's mouth rather than sliding on
            // with the rest of the line: it is being held. Letting it drift by the
            // segment fraction carried it back behind the buddy mid-bite, and on a short
            // timer -- where a segment is only a few seconds long -- it slid far enough
            // that the bites were never on screen at all.
            float lane = (k == 0 && beat >= 0f) ? 0f : k - fraction;
            float x = base + lane * spacing;
            // Items belong to the lane, so they stop where it does. Clipping at the
            // goal's box instead cut them a good deal earlier than the chest actually
            // reaches, since its art does not fill that box.
            if (x < -size || x > layout.advTrail.right + size * 0.35f) continue;

            float y = ground + (float) Math.sin(t * 1.6f + index) * size * 0.06f;
            if (k == 0) {
                // The one being eaten: whole, then a bite gone, then two, then nothing.
                // Each bite pops as it lands, which is what makes it read as a bite
                // rather than the item quietly changing shape.
                int bites = Engine.bitesTaken(beat);
                if (bites >= Art.BITE_COUNT) continue;
                float eaten = Engine.eatPhase(beat);
                float pop = Math.max(0f, 1f - Math.abs(eaten - bites) * 6f);
                Icons.collectible(c, theme.index, x, y, size, true, pop, false, bites);
            } else {
                Icons.collectible(c, theme.index, x, y, size, true, 0f, false);
            }
        }

        if (inMouth) fireBurst(layout, theme, engine, collected, base, ground);
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

    private void drawGoal(Canvas c, Layout layout, BuddyTheme theme, Engine engine) {
        RectF box = layout.advGoal;
        float size = Math.min(box.width(), box.height());
        boolean reached = engine.allDone();
        Icons.goal(c, theme.index, box.centerX(), box.centerY(), size,
                   reached ? 1f : 0f, reached ? 1f : 0f);
        if (!reached) {
            // Over the goal, not beneath it: below, it collided with the progress bar.
            Icons.goalLocked(c, box.centerX(), box.centerY() + size * 0.08f, size);
        }
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

    /**
     * The score: one large collectible and how many have been eaten.
     *
     * <p>A row of one thumbnail per item was the old shape, and it could not survive a
     * long morning -- eighteen of anything across a phone is eighteen things too small
     * to recognise. A single item at a size you can actually see, with a count beside
     * it, says the same thing and keeps saying it however long the timer runs.
     */
    /**
     * Every treat in the morning, laid out in rows above the lane.
     *
     * <p>The trail only ever shows the two or three the buddy is walking between, so a
     * ninety-minute morning with two dozen treats in it looked exactly like a five-minute
     * one. This is the whole set at once: eaten ones in full colour with their check,
     * the rest waiting in grey, filling the empty sky the scene otherwise wastes.
     *
     * <p>The grid is solved rather than fixed. Every column count is tried and the one
     * giving the largest tile wins, so three treats are big and two dozen still fit.
     */
    private void drawTreatBoard(Canvas c, Layout layout, BuddyTheme theme, Engine engine,
                                float t) {
        int total = engine.collectibleCount();
        if (total <= 0) return;
        int collected = engine.collectedCount();

        // The sky between the tally chip and the top of the buddy.
        float top = layout.advTally.bottom + 24f;
        float bottom = layout.advTrail.centerY() - buddyHeight(layout) - 24f;
        float left = layout.advScene.left + 70f;
        float right = layout.advScene.right - 70f;
        if (bottom - top < 60f || right - left < 60f) return;

        float boardW = right - left, boardH = bottom - top;
        // Capped so a three-treat morning does not show three dinner plates.
        float maxCell = Layout.W * 0.115f / 0.82f;

        int bestCols = 1;
        float bestCell = 0f;
        for (int cols = 1; cols <= total; cols++) {
            int rows = (total + cols - 1) / cols;
            float cell = Math.min(boardW / cols, boardH / rows);
            if (cell > bestCell) { bestCell = cell; bestCols = cols; }
        }
        // Once the tile is at its cap, a taller grid buys nothing and just stacks three
        // treats into a ragged two-by-two. Among the layouts that still reach the size
        // we are going to draw at, take the widest -- the fewest rows.
        float target = Math.min(bestCell, maxCell);
        int cols = bestCols;
        for (int candidate = total; candidate >= 1; candidate--) {
            int rows = (total + candidate - 1) / candidate;
            if (Math.min(boardW / candidate, boardH / rows) >= target - 0.01f) {
                cols = candidate;
                break;
            }
        }
        int rows = (total + cols - 1) / cols;
        // Even the rows out: eight treats read better as four and four than six and two.
        cols = (total + rows - 1) / rows;
        float size = Math.min(Math.min(boardW / cols, boardH / rows) * 0.82f,
                              Layout.W * 0.115f);
        float cellW = boardW / cols;
        float cellH = Math.min(boardH / rows, size * 1.5f);
        float gridTop = top + (boardH - cellH * rows) * 0.5f;

        for (int i = 0; i < total; i++) {
            int row = i / cols, col = i % cols;
            int inRow = Math.min(cols, total - row * cols);
            float rowLeft = left + (boardW - inRow * cellW) * 0.5f;
            float x = rowLeft + col * cellW + cellW * 0.5f;
            float y = gridTop + row * cellH + cellH * 0.5f
                    + (float) Math.sin(t * 1.3f + i * 0.7f) * size * 0.05f;
            // No check badge here: on a board of two dozen the green discs swamped the
            // treats themselves, and colour against grey already says which are gone.
            boolean got = i < collected;
            Icons.collectible(c, theme.index, x, y, size, got, 0f, false);
        }
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
