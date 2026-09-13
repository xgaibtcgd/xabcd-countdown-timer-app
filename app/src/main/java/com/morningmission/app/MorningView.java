package com.morningmission.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * The single view the whole app is drawn into.
 *
 * <p>It owns the frame clock, the screen stack, touch dispatch and the bitmap lifecycle.
 * Everything else lives in {@link Layout}, {@link Scene}, {@link Engine} and the
 * {@link Screen} subclasses.
 *
 * <p>Three things changed from the old inner-class version, all of which were costing
 * either frames or correctness:
 *
 * <p><b>It renders on the GPU.</b> The old view called
 * {@code setLayerType(LAYER_TYPE_SOFTWARE)} so that {@code Paint.setShadowLayer} would
 * work on shapes below API 28, which meant rasterising the entire screen on the CPU
 * thirty times a second. Shadows are now drawn as offset round rects, so that line is
 * gone.
 *
 * <p><b>The clock is a real clock.</b> A fixed 33ms Handler loop fed
 * {@code SystemClock.uptimeMillis()} straight into every sine, ran whether or not
 * anything was moving, and had no stop path -- it kept posting after the activity was
 * destroyed. It is now a Choreographer callback with a real delta time, it stops when the
 * screen is still, and it stops on detach.
 *
 * <p><b>Touch has a press state.</b> The old handler only looked at ACTION_UP, so nothing
 * in the app could respond to being held. Every control now has a pressed state.
 */
final class MorningView extends View implements Choreographer.FrameCallback, Engine.Listener {

    static final int SCREEN_HOME = 0;
    static final int SCREEN_ADVENTURE = 1;
    static final int SCREEN_COMPLETE = 2;
    static final int SCREEN_BUDDY_PICKER = 3;
    static final int SCREEN_TIME_PICKER = 4;
    static final int SCREEN_GROWN_UPS = 5;
    static final int SCREEN_EDIT_ROUTINE = 6;
    /** Package-visible so tools/SelfTest.java can sweep every screen. */
    static final int SCREEN_COUNT = 7;

    /** How long a change of screen takes to settle. */
    private static final float ROUTE_SECONDS = 0.24f;
    /** How long the buddy cheers after a task before returning to the next one. */
    private static final float CHEER_SECONDS = 1.3f;

    final MainActivity activity;
    final Layout layout = new Layout();
    final HitMap hits = new HitMap();
    final Scene scene = new Scene();
    final Particles particles = new Particles();
    final Engine engine;

    private final Screen[] screens = new Screen[SCREEN_COUNT];
    /**
     * The screen the app opens on.
     *
     * <p>Named rather than written into the field below, so tools/SelfTest.java can hold
     * it against {@link #wantsTitleMusic}: "the app opens playing the title song" is a
     * property of those two together, and nothing else states it.
     */
    static final int INITIAL_SCREEN = SCREEN_HOME;

    private int current = INITIAL_SCREEN;
    private int previous = SCREEN_HOME;
    private float routeProgress = 1f;

    private final Anim.Blend blend = new Anim.Blend();
    /** Solved separately so a collectible action can be composed onto the walk. */
    private final Anim.Transform feastMotion = new Anim.Transform();
    private final Anim.Transform motion = new Anim.Transform();
    /** Where the body was a beat ago; what a rigged appendage trails against. */
    private final Anim.Transform laggedMotion = new Anim.Transform();
    private final int[] palette = new int[6];
    private final RectF scratch = new RectF();

    // Buddy artwork, loaded on demand. The selected buddy is decoded at full size; the
    // others are halved, since they are only ever seen at picker size.
    private final Bitmap[] art = new Bitmap[BuddyTheme.COUNT];
    private final int[] artSample = new int[BuddyTheme.COUNT];
    // One slot, not eight: exactly one buddy ever cheers, on one screen.
    private Bitmap cheerBitmap;
    private int cheerIndex = -1;
    // One rigged buddy at a time. Five trimmed parts come to less than the flat sprite
    // they replace, so this is a saving rather than a cost.
    private final Bitmap[] rigBitmap = new Bitmap[Rig.PART_COUNT];
    private int rigIndex = -1;
    private float cheerTop;
    private int[] scanRow;
    private final Bitmap[] props = new Bitmap[PROP_COUNT];

    // Backgrounds are large, and at most two are ever wanted at once: the storybook
    // meadow every non-adventure screen sits on, and the current buddy's world.
    private static final int BACKDROP_CACHE = 2;
    private final int[] backdropRes = new int[BACKDROP_CACHE];
    private final Bitmap[] backdropBitmap = new Bitmap[BACKDROP_CACHE];
    private int backdropNext;

    private boolean measured;
    private boolean attached;
    private long lastFrameNanos;
    /** Seconds since attach, the phase input for every animation. */
    private float time;
    private float lastDelta = 1f / 60f;
    private float cheerRemaining;

    // Touch state.
    private int pressedIndex = HitMap.NONE;
    private float pressAmount;
    private float downX, downY;
    private boolean dragging;
    private boolean scrolling;
    private float lastScrollY;
    private static final float TOUCH_SLOP = 24f;

    private int insetTopPx, insetBottomPx;
    /** Draws every registered hit region, for checking that taps land where they look. */
    boolean debugRegions;

    MorningView(Context context, MainActivity activity) {
        super(context);
        this.activity = activity;
        Theme.init(context);
        engine = new Engine(android.os.SystemClock::elapsedRealtime);
        engine.setListener(this);

        screens[SCREEN_HOME] = new ScreenHome(this);
        screens[SCREEN_ADVENTURE] = new ScreenAdventure(this);
        screens[SCREEN_COMPLETE] = new ScreenComplete(this);
        screens[SCREEN_BUDDY_PICKER] = new ScreenBuddyPicker(this);
        screens[SCREEN_TIME_PICKER] = new ScreenTimePicker(this);
        screens[SCREEN_GROWN_UPS] = new ScreenGrownUps(this);
        screens[SCREEN_EDIT_ROUTINE] = new ScreenEditRoutine(this);

        reloadRoutine();
        blend.snap(Anim.SLEEPY);
    }

    // ----------------------------------------------------------------- preferences

    BuddyTheme buddy() {
        return BuddyTheme.of(activity.prefs.getInt("buddy", 3));
    }

    void setBuddy(int index) {
        int safe = BuddyTheme.clampIndex(index);
        activity.prefs.edit().putInt("buddy", safe).apply();
        rebuildScene();
        invalidate();
    }

    /** Where the duration is stored, in seconds. See {@link #durationSeconds()}. */
    static final String DURATION_KEY = "duration_seconds";
    static final int MIN_DURATION_SECONDS = 15;
    static final int MAX_DURATION_SECONDS = 120 * 60;

    /**
     * The chosen length of the morning, in seconds.
     *
     * <p>Stored under a different key from the old whole-minute {@code "minutes"}:
     * writing seconds into that one would have turned an existing fifteen-minute morning
     * into fifteen seconds. When the new key is absent the old one is read and scaled,
     * so an upgrade keeps the length the household already had.
     */
    int durationSeconds() {
        int stored = activity.prefs.getInt(DURATION_KEY, 0);
        if (stored <= 0) stored = activity.prefs.getInt("minutes", 15) * 60;
        return Math.max(MIN_DURATION_SECONDS, Math.min(MAX_DURATION_SECONDS, stored));
    }

    void setDurationSeconds(int value) {
        int safe = Math.max(MIN_DURATION_SECONDS, Math.min(MAX_DURATION_SECONDS, value));
        activity.prefs.edit().putInt(DURATION_KEY, safe).apply();
        engine.setDurationSeconds(safe);
        invalidate();
    }

    boolean pref(String key, boolean fallback) {
        return activity.prefs.getBoolean(key, fallback);
    }

    void setPref(String key, boolean value) {
        activity.prefs.edit().putBoolean(key, value).apply();
        // Both the Grown-Ups row and the Adventure mute chip come through here, so one
        // guard covers them: turning sounds off has to stop the five-second fanfare
        // that may be playing, not just prevent the next one.
        if ("song".equals(key) && !value) activity.silence();
        invalidate();
    }

    void reloadRoutine() {
        engine.setRoutine(activity.loadTaskNames(), activity.loadTaskKeys());
        requestLayoutPass();
        invalidate();
    }

    // --------------------------------------------------------------------- routing

    int currentScreen() { return current; }

    /**
     * Which screens the title loop plays under.
     *
     * <p>Home only -- it is the title screen's song. Its own method rather than the test
     * written out twice, because the two places that ask are a route and an attach, and
     * the whole reason the song used to be missing on first launch is that only one of
     * them was asking.
     */
    static boolean wantsTitleMusic(int screen) { return screen == SCREEN_HOME; }

    void route(int screen) {
        if (screen == current || screen < 0 || screen >= SCREEN_COUNT) return;
        previous = current;
        current = screen;
        routeProgress = 0f;
        // onEnter first: a screen loads its working state there, and laying out before
        // that would register hit regions for the state it is replacing. The routine
        // editor in particular starts empty and fills itself in onEnter.
        screens[current].onEnter();
        // Every route but this one. Arriving at the Complete screen the whoosh lands
        // 780ms into the fanfare, on top of its first bar.
        if (screen != SCREEN_COMPLETE) activity.playUi(Sounds.UI_PAGE);
        activity.setTitleMusic(wantsTitleMusic(current));
        requestLayoutPass();
        rebuildScene();
        startClock();
        invalidate();
    }

    /** How many rows the routine editor is showing, which may include unsaved edits. */
    private int editorRowCount() {
        Screen editor = screens[SCREEN_EDIT_ROUTINE];
        return editor instanceof ScreenEditRoutine
               ? ((ScreenEditRoutine) editor).rowCount() : engine.taskCount();
    }

    /** Re-runs the current screen's layout and re-registers its hit regions. */
    void requestLayoutPass() {
        if (getWidth() == 0 || getHeight() == 0) return;
        layout.measure(getWidth(), getHeight(), insetTopPx, insetBottomPx,
                       getResources().getDisplayMetrics().densityDpi,
                       engine.taskCount(), editorRowCount());
        measured = true;
        hits.clear();
        screens[current].layout(layout, hits);
    }

    void setSystemInsets(int top, int bottom) {
        insetTopPx = top;
        insetBottomPx = bottom;
        requestLayoutPass();
        rebuildScene();
        invalidate();
    }

    private void rebuildScene() {
        if (!measured) return;
        int mode = current == SCREEN_COMPLETE ? Scene.MODE_CELEBRATE
                 : current == SCREEN_ADVENTURE ? Scene.MODE_ADVENTURE
                 : Scene.MODE_HOME;
        scene.rebuild(layout.play, buddy(), mode, backdropFor(mode));
    }

    /**
     * The illustrated background for a mode, or null to let {@link Scene} draw one.
     *
     * <p>The adventure happens in the buddy's own world; everything else sits on the
     * storybook meadow, as the mockups show. The celebration has no artwork of its own,
     * so it keeps the drawn golden burst.
     */
    private Bitmap backdropFor(int mode) {
        if (mode == Scene.MODE_CELEBRATE) return null;
        int res = mode == Scene.MODE_ADVENTURE
                ? buddy().backdropRes
                : R.drawable.bg_home_storybook;
        return backdrop(res);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        requestLayoutPass();
        rebuildScene();
    }

    // ----------------------------------------------------------------- frame clock

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        lastFrameNanos = 0L;
        // The title loop follows the screen, and {@link #route} is what moves it -- but
        // route returns early when the screen is not changing, so the screen the app
        // OPENS on never asked for anything and the song did not start until you had
        // left Home and come back to it. Attaching is the one moment the current screen
        // is current without having been routed to, so it is where the gap was.
        activity.setTitleMusic(wantsTitleMusic(current));
        startClock();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        Choreographer.getInstance().removeFrameCallback(this);
        releaseArt();
        super.onDetachedFromWindow();
    }

    /** Called from the activity's onPause, so a backgrounded app stops drawing. */
    void stopClock() {
        Choreographer.getInstance().removeFrameCallback(this);
    }

    void startClock() {
        if (!attached) return;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!attached) return;
        float dt = lastFrameNanos == 0L ? 1f / 60f : (frameTimeNanos - lastFrameNanos) / 1e9f;
        lastFrameNanos = frameTimeNanos;
        // A long frame, after a stall or a return from background, must not be integrated
        // as one enormous step.
        if (dt > 0.05f) dt = 0.05f;
        lastDelta = dt;
        time += dt;

        if (routeProgress < 1f) routeProgress = Math.min(1f, routeProgress + dt / ROUTE_SECONDS);
        if (cheerRemaining > 0f) cheerRemaining = Math.max(0f, cheerRemaining - dt);
        if (pressedIndex == HitMap.NONE) {
            pressAmount = Math.max(0f, pressAmount - dt * 6f);
        } else {
            pressAmount = Math.min(1f, pressAmount + dt * 9f);
        }

        engine.pollTimeUp();
        // Beside pollTimeUp for the same reason: eating is a pure function of the clock,
        // so the only honest place to notice a bite has landed is the frame loop, not the
        // draw call that happens to render it.
        int bite = engine.pollBite();
        if (bite >= 0) activity.playEatSound(buddy().index, bite);
        if (engine.pollMilestone()) activity.playCue(Sounds.CUE_MILESTONE);
        // The chest at the end of the lane. Ahead of the ticks by design -- the prize
        // window is a couple of seconds wider than the countdown, so the lid is already
        // swinging when the last ten seconds start marking themselves off.
        if (engine.pollPrize()) activity.playCue(Sounds.CUE_GOAL);
        if (engine.pollTick() > 0) activity.playCue(Sounds.CUE_TICK);
        particles.update(dt);
        blend.set(Anim.stateFor(engine, pref("dance", true), cheerRemaining, engine.isRunning()));
        blend.update(dt);

        invalidate();
        if (attached && (screens[current].animating() || routeProgress < 1f
                         || particles.isActive() || pressAmount > 0f)) {
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    // ------------------------------------------------------------------------ draw

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (!measured) return;
        c.save();
        c.scale(layout.scale, layout.scale);
        screens[current].draw(c, layout, time, lastDelta);

        if (routeProgress < 1f) {
            // A soft dissolve in the buddy's own tint, rather than a hard cut. Cheaper
            // than an offscreen layer and warmer than a white flash.
            Paint fill = Theme.FILL;
            fill.setShader(null);
            fill.setColor(Theme.alpha(buddy().light, (int) (215 * (1f - routeProgress))));
            c.drawRect(0f, 0f, Layout.W, layout.height, fill);
        }

        if (debugRegions) drawDebugRegions(c);
        c.restore();
    }

    private void drawDebugRegions(Canvas c) {
        Paint stroke = Theme.STROKE;
        stroke.setShader(null);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3f);
        for (int i = 0; i < hits.size(); i++) {
            stroke.setColor(i == pressedIndex ? 0xFFFF3B30 : 0x8800E5FF);
            c.drawRect(hits.rectAt(i), stroke);
        }
    }

    // ---------------------------------------------------------------- buddy drawing

    /**
     * Draws the chosen buddy with its current motion.
     *
     * <p>Scaling happens about the feet rather than the centre, so squash reads as
     * weight on the ground instead of the character inflating in place.
     */
    void drawBuddy(Canvas c, int buddyIndex, float cx, float feetY, float height,
                   boolean withShadow) {
        drawBuddy(c, buddyIndex, cx, feetY, height, withShadow, -1, -1f);
    }

    /**
     * The same, with a collectible action layered on top of the walk.
     *
     * <p>The two compose rather than replace: offsets add and scales multiply, so the
     * buddy keeps breathing and bobbing through its chomp instead of freezing into a
     * canned clip.
     *
     * @param moveId    from {@link Anim#feastMoveId} or {@link Anim#signatureMoveId},
     *                  or -1 for no action
     * @param movePhase 0..1 across the action, or negative when there is none
     */
    void drawBuddy(Canvas c, int buddyIndex, float cx, float feetY, float height,
                   boolean withShadow, int moveId, float movePhase) {
        drawBuddy(c, buddyIndex, cx, feetY, height, withShadow, moveId, movePhase, 1f);
    }

    /** The same, with the action scaled -- see {@link Anim#move(int, float, float, Anim.Transform)}. */
    void drawBuddy(Canvas c, int buddyIndex, float cx, float feetY, float height,
                   boolean withShadow, int moveId, float movePhase, float moveStrength) {
        drawBuddy(c, buddyIndex, cx, feetY, height, withShadow,
                  moveId, movePhase, moveStrength, false);
    }

    /**
     * The same, facing the other way.
     *
     * <p>Every character is drawn facing right, which was fine while the adventure was a
     * walk from the left edge to the right one. The route winds now, and half its
     * segments run right to left -- a buddy that kept facing right on those would be
     * moonwalking to the treasure.
     */
    void drawBuddy(Canvas c, int buddyIndex, float cx, float feetY, float height,
                   boolean withShadow, int moveId, float movePhase, float moveStrength,
                   boolean faceLeft) {
        // A rigged buddy draws its parts instead; passing null is what selects that.
        Bitmap bitmap = BuddyTheme.of(buddyIndex).rig != null
                ? null : art(buddyIndex, buddyIndex == buddy().index);
        if (bitmap == null && BuddyTheme.of(buddyIndex).rig == null) return;
        if (bitmap != null && bitmap.isRecycled()) return;
        drawSprite(c, bitmap, buddyIndex, cx, feetY, height, withShadow,
                   moveId, movePhase, moveStrength, faceLeft);
    }

    /** The shared body of the two above: motion, contact shadow, transform, draw. */
    private void drawSprite(Canvas c, Bitmap bitmap, int buddyIndex, float cx, float feetY,
                            float height, boolean withShadow, int moveId, float movePhase,
                            float moveStrength, boolean faceLeft) {
        // Called once per frame, which is what lets the blend track velocity for squash
        // and stretch. drawBuddyPose below solves without touching that state, so the
        // seven dancing buddies in the picker cannot disturb it.
        blend.solve(time, lastDelta, BuddyTheme.of(buddyIndex).temperament, motion);
        if (moveId >= 0 && movePhase >= 0f && movePhase < 1f) {
            Anim.move(moveId, movePhase, moveStrength, feastMotion);
            motion.dx += feastMotion.dx;
            motion.dy += feastMotion.dy;
            motion.rotation += feastMotion.rotation;
            motion.scaleX *= feastMotion.scaleX;
            motion.scaleY *= feastMotion.scaleY;
        }
        paintSprite(c, bitmap, buddyIndex, cx, feetY, height, withShadow, time,
                    blend.state(), faceLeft);
    }

    /**
     * Contact shadow, outer transform, and whatever fills the frame.
     *
     * <p>Split out of {@link #drawSprite} because {@link #drawBuddyPose} used to carry
     * its own copy of this transform chain, and two copies of a chain this fiddly drift.
     * Everything above the split decides the motion; everything below paints it.
     *
     * @param bitmap the flat sprite, or null to let a rigged buddy draw its parts
     * @param at     the clock the part animation reads; the picker offsets it per card
     * @param animState the state the part animation reads. The picker passes its own
     *                  rather than the blend's, for the same reason it solves its own
     *                  motion: eight cards must not disturb the state the app is riding.
     * @param faceLeft  mirrors the whole frame, rig included, about the character's own
     *                  centre. The motion's own dx mirrors with it so that "forward"
     *                  keeps meaning forward -- a lunge has to go the way the character
     *                  is looking, not always to the right.
     */
    private void paintSprite(Canvas c, Bitmap bitmap, int buddyIndex, float cx, float feetY,
                             float height, boolean withShadow, float at, int animState,
                             boolean faceLeft) {
        BuddyTheme theme = BuddyTheme.of(buddyIndex);
        Rig rig = bitmap == null ? theme.rig : null;
        if (rig == null && bitmap == null) return;

        float width = rig != null
                ? height * rig.aspect
                : height * bitmap.getWidth() / (float) bitmap.getHeight();
        float centreY = feetY - height * 0.5f + motion.dy;
        float dx = faceLeft ? -motion.dx : motion.dx;

        if (withShadow) {
            float squash = Theme.clamp(motion.scaleY, 0.85f, 1.15f);
            Clay.contactShadow(c, cx + dx, feetY + height * 0.02f,
                               width * 0.36f * (2f - squash), height * 0.055f,
                               Theme.clamp(1f - Math.abs(motion.dy) / 90f, 0.25f, 1f));
        }

        c.save();
        c.translate(cx + dx, centreY);
        // Before the rotate, so a lean mirrors too: a character tipped into its stride
        // has to tip the way it is walking.
        if (faceLeft) c.scale(-1f, 1f);
        c.rotate(motion.rotation);
        c.scale(motion.scaleX, motion.scaleY, 0f, height * 0.42f);
        Theme.BMP.setAlpha(255);
        if (rig != null) {
            drawRig(c, rig, theme, buddyIndex, width, height, at, animState);
        } else {
            scratch.set(-width * 0.5f, -height * 0.5f, width * 0.5f, height * 0.5f);
            c.drawBitmap(bitmap, null, scratch, Theme.BMP);
        }
        c.restore();
    }

    /**
     * Five parts inside the outer transform, each turning about its own pivot.
     *
     * <p>The lag -- an appendage trailing the body rather than moving with it -- comes
     * from sampling the same motion function {@link Anim#RIG_LAG} seconds ago and
     * differencing. No history is kept, so nothing has to be reset when the screen
     * changes and a paused clock stays correct for free.
     */
    private void drawRig(Canvas c, Rig rig, BuddyTheme theme, int buddyIndex,
                         float width, float height, float at, int animState) {
        Anim.solve(animState, theme.temperament, at - Anim.RIG_LAG, laggedMotion);
        float trail = motion.dy - laggedMotion.dy;

        for (int slot = 0; slot < Rig.PART_COUNT; slot++) {
            // Draw ORDER, which is not part order for every character: Burger Buddy's
            // signature part is the cheeseburger it is holding, and that goes in front.
            int part = rig.partAt(slot);
            Bitmap piece = rigPart(rig, buddyIndex, part);
            if (piece == null || piece.isRecycled()) continue;
            float w = rig.width(part) * width;
            float h = rig.height(part) * height;
            float px = (rig.pivotX(part) - 0.5f) * w;
            float py = (rig.pivotY(part) - 0.5f) * h;
            float degrees = rig.rest(part)
                    + Anim.partAngle(part, theme.temperament, at, trail, motion.rotation,
                                     rig.signatureBeat, rig.signatureSweep);
            c.save();
            c.translate((rig.cx(part) - 0.5f) * width, (rig.cy(part) - 0.5f) * height);
            c.rotate(degrees, px, py);
            float squash = Anim.partScaleY(part, at, rig.signatureBeat,
                                           rig.signatureSquash);
            if (squash != 1f) c.scale(1f, squash, px, py);
            scratch.set(-w * 0.5f, -h * 0.5f, w * 0.5f, h * 0.5f);
            c.drawBitmap(piece, null, scratch, Theme.BMP);
            c.restore();
        }
    }

    /**
     * The buddy with its arms up, for the celebration.
     *
     * <p>The cheer artwork is fitted to the walking sprite's own framing, so this is the
     * same call with a different bitmap -- same height, same feet, same centre. Falls
     * back to the walking sprite if the decode fails, which is a buddy that celebrates
     * without raising its arms rather than a Complete screen with nothing on it.
     */
    void drawBuddyCheering(Canvas c, int buddyIndex, float cx, float feetY, float height,
                           boolean withShadow) {
        // A rigged buddy has no cheer bitmap and does not need one: its arms are their
        // own parts, and Anim.DANCE already has them up.
        if (BuddyTheme.of(buddyIndex).rig != null) {
            drawBuddy(c, buddyIndex, cx, feetY, height, withShadow);
            return;
        }
        Bitmap bitmap = cheer(buddyIndex);
        if (bitmap == null || bitmap.isRecycled()) {
            drawBuddy(c, buddyIndex, cx, feetY, height, withShadow);
            return;
        }
        drawSprite(c, bitmap, buddyIndex, cx, feetY, height, withShadow, -1, -1f, 1f,
                   false);
    }

    /** Draws a buddy with a fixed pose, for the picker where seven dance at once. */
    void drawBuddyPose(Canvas c, int buddyIndex, float cx, float feetY, float height,
                       int state, float phaseOffset) {
        // The FLAT sprite here, even for a rigged character. The picker lays out all
        // eight at once and the rig bitmap cache holds one buddy's parts, so rigging
        // this path would evict and re-decode five PNGs eight times a frame. Caching
        // all of them instead costs 9.6MB to animate limbs on a thumbnail nobody is
        // looking at that closely; seven of the eight were flat here until today.
        Bitmap bitmap = art(buddyIndex, false);
        if (bitmap == null) return;
        // Solved statelessly, so the eight dancing cards cannot disturb the blend the
        // rest of the app is riding.
        Anim.solve(state, BuddyTheme.of(buddyIndex).temperament, time + phaseOffset, motion);
        paintSprite(c, bitmap, buddyIndex, cx, feetY, height, false, time + phaseOffset,
                    state, false);
    }

    /**
     * Decodes buddy artwork on demand.
     *
     * <p>The old view decoded all fifteen images eagerly and never recycled any of them:
     * around 77MB of bitmap heap, of which at most two were ever on screen. Deleting the
     * backgrounds removed 63MB of that; the rest is this. Only the chosen buddy is kept
     * at full resolution.
     */
    private Bitmap art(int index, boolean fullResolution) {
        int i = BuddyTheme.clampIndex(index);
        int wanted = fullResolution ? 1 : 2;
        if (art[i] != null && !art[i].isRecycled() && artSample[i] <= wanted) return art[i];

        BitmapFactory.Options options = new BitmapFactory.Options();   // allocgate: ok - decode path, not a frame path
        options.inSampleSize = wanted;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeResource(
                getResources(), BuddyTheme.of(i).artRes, options);
        if (decoded == null) return art[i];
        if (art[i] != null && !art[i].isRecycled()) art[i].recycle();
        art[i] = decoded;
        artSample[i] = wanted;
        return decoded;
    }

    /**
     * The chest opening, frame 0 closed through frame 4 wide open and emptied.
     *
     * <p>The chest and its star are shared by every character, so they are here rather
     * than on BuddyTheme.
     *
     * <p>Five frames of one render rather than two states and a tween: the lid swings on a
     * hinge the app cannot fake by rotating a flat bitmap, and the interior is only drawn
     * at all once the lid is off it. They are registered on the chest's own base, so the
     * box holds still and only the lid moves.
     */
    static final int PROP_CHEST_0 = 0, PROP_CHEST_FRAMES = 5;
    /** Lid wide open with the treasure lit inside -- the peak of the reveal. */
    static final int PROP_CHEST_FULL = 3;
    /** Lid all the way back, the star gone: where the opening settles. */
    static final int PROP_CHEST_OPEN = 4;
    static final int PROP_STAR = PROP_CHEST_FRAMES;

    private static final int[] PROP_RES = {
        R.drawable.prize_chest_0, R.drawable.prize_chest_1, R.drawable.prize_chest_2,
        R.drawable.prize_chest_3, R.drawable.prize_chest_4, R.drawable.prize_star,
    };

    /**
     * Derived, not written down: the cache array, the bounds check and the resource table
     * have to be the same length, and a hand-kept count is one of the three going stale.
     * It already did -- the cache stayed at three while the chest grew to five frames,
     * which indexes past the end of it the moment the lid moves.
     */
    static final int PROP_COUNT = PROP_RES.length;

    /** The chest frame for an opening {@code 0..1}, held on the last one at the end. */
    static int chestFrame(float open) {
        if (open <= 0f) return PROP_CHEST_0;
        if (open >= 1f) return PROP_CHEST_OPEN;
        int frame = (int) (open * PROP_CHEST_FRAMES);
        return frame >= PROP_CHEST_FRAMES ? PROP_CHEST_FRAMES - 1 : frame;
    }

    /**
     * Decodes one of the shared prize images, on demand and once.
     *
     * <p>Guarded the way {@link #backdrop} is rather than the way {@link #art} is: three
     * more bitmaps on a heap with no {@code largeHeap} is worth failing softly over, and
     * the drawing code treats null as "nothing to draw".
     */
    Bitmap prop(int which) {
        if (which < 0 || which >= PROP_COUNT) return null;
        if (props[which] != null && !props[which].isRecycled()) return props[which];
        try {
            // allocgate: ok - decode path, reached once per image
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            props[which] = BitmapFactory.decodeResource(getResources(), PROP_RES[which], options);
        } catch (OutOfMemoryError | Exception e) {
            props[which] = null;
        }
        return props[which];
    }

    /**
     * Draws a prop bitmap centred on a point, fitted to {@code size} on its longer side.
     *
     * @param spin degrees, about the centre
     */
    void drawProp(Canvas c, int which, float cx, float cy, float size, float spin, int alpha) {
        Bitmap bitmap = prop(which);
        if (bitmap == null || bitmap.isRecycled()) return;
        float longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        float w = size * bitmap.getWidth() / longest;
        float h = size * bitmap.getHeight() / longest;
        c.save();
        c.translate(cx, cy);
        if (spin != 0f) c.rotate(spin);
        scratch.set(-w * 0.5f, -h * 0.5f, w * 0.5f, h * 0.5f);
        Theme.BMP.setAlpha(alpha);
        c.drawBitmap(bitmap, null, scratch, Theme.BMP);
        Theme.BMP.setAlpha(255);
        c.restore();
    }

    /**
     * Decodes the current buddy's cheer artwork.
     *
     * <p>One slot. The celebration shows one character and the picker never cheers, so a
     * per-buddy cache would hold seven bitmaps nothing is going to ask for.
     */
    private Bitmap cheer(int index) {
        int i = BuddyTheme.clampIndex(index);
        if (cheerIndex == i && cheerBitmap != null && !cheerBitmap.isRecycled()) {
            return cheerBitmap;
        }
        if (cheerBitmap != null && !cheerBitmap.isRecycled()) cheerBitmap.recycle();
        cheerBitmap = null;
        cheerIndex = -1;
        try {
            // allocgate: ok - decode path, reached once per celebration
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            cheerBitmap = BitmapFactory.decodeResource(
                    getResources(), BuddyTheme.of(i).cheerRes, options);
            if (cheerBitmap != null) {
                cheerIndex = i;
                cheerTop = topFraction(cheerBitmap);
            }
        } catch (OutOfMemoryError | Exception e) {
            cheerBitmap = null;
            cheerIndex = -1;
        }
        return cheerBitmap;
    }

    /**
     * How far down its own frame a sprite's artwork starts, 0..1.
     *
     * <p>For anything that has to sit ON the character rather than on the frame -- the
     * celebration crown, which was floating a tenth of a body above some heads because
     * the frames are padded and the padding is not the same on every pose.
     *
     * <p>Row at a time from the top, stopping at the first one with any ink in it, so a
     * character occupying most of its frame costs a few dozen rows rather than the lot.
     */
    private float topFraction(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return 0f;
        // allocgate: ok - decode path, reached once per celebration
        if (scanRow == null || scanRow.length < w) scanRow = new int[w];
        for (int y = 0; y < h; y++) {
            bitmap.getPixels(scanRow, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((scanRow[x] >>> 24) > 8) return y / (float) h;
            }
        }
        return 0f;
    }

    /**
     * The top of the celebrating buddy's artwork within its frame, 0..1.
     *
     * <p>Zero if the cheer art is missing, which is also right: the fallback is the
     * walking sprite, and this is only ever used to nudge something downward.
     */
    float cheerTopFraction(int index) {
        Rig rig = BuddyTheme.of(index).rig;
        if (rig != null) return rig.topFraction();
        return cheer(index) == null ? 0f : cheerTop;
    }

    /**
     * Decodes one part of the rigged buddy's artwork.
     *
     * <p>Guarded like {@link #cheer} rather than like {@link #art}: a failed decode
     * returns null and that part is skipped, which is a bee missing a wing rather than
     * a crash. Switching buddy drops the whole set at once, since the parts are only
     * meaningful together.
     */
    private Bitmap rigPart(Rig rig, int index, int part) {
        int i = BuddyTheme.clampIndex(index);
        if (rigIndex != i) {
            releaseRig();
            rigIndex = i;
        }
        if (rigBitmap[part] != null && !rigBitmap[part].isRecycled()) return rigBitmap[part];
        try {
            // allocgate: ok - decode path, reached once per part per buddy
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            rigBitmap[part] = BitmapFactory.decodeResource(
                    getResources(), rig.res[part], options);
        } catch (OutOfMemoryError | Exception e) {
            rigBitmap[part] = null;
        }
        return rigBitmap[part];
    }

    private void releaseRig() {
        for (int i = 0; i < Rig.PART_COUNT; i++) {
            if (rigBitmap[i] != null && !rigBitmap[i].isRecycled()) rigBitmap[i].recycle();
            rigBitmap[i] = null;
        }
        rigIndex = -1;
    }

    /** Decodes an illustrated background, keeping the last two. */
    private Bitmap backdrop(int res) {
        if (res == 0) return null;
        for (int i = 0; i < BACKDROP_CACHE; i++) {
            if (backdropRes[i] == res && backdropBitmap[i] != null && !backdropBitmap[i].isRecycled()) {
                return backdropBitmap[i];
            }
        }
        Bitmap decoded;
        try {
            // allocgate: ok - decode path, reached only on a size or screen change
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;   // no alpha in these
            decoded = BitmapFactory.decodeResource(getResources(), res, options);
        } catch (OutOfMemoryError | Exception e) {
            return null;                        // Scene falls back to drawing the scenery
        }
        if (decoded == null) return null;
        int slot = backdropNext;
        backdropNext = (backdropNext + 1) % BACKDROP_CACHE;
        if (backdropBitmap[slot] != null && !backdropBitmap[slot].isRecycled()) {
            backdropBitmap[slot].recycle();
        }
        backdropRes[slot] = res;
        backdropBitmap[slot] = decoded;
        return decoded;
    }

    private void releaseArt() {
        releaseRig();
        if (cheerBitmap != null && !cheerBitmap.isRecycled()) cheerBitmap.recycle();
        cheerBitmap = null;
        cheerIndex = -1;
        for (int i = 0; i < PROP_COUNT; i++) {
            if (props[i] != null && !props[i].isRecycled()) props[i].recycle();
            props[i] = null;
        }
        for (int i = 0; i < art.length; i++) {
            if (art[i] != null && !art[i].isRecycled()) art[i].recycle();
            art[i] = null;
            artSample[i] = 0;
        }
        for (int i = 0; i < BACKDROP_CACHE; i++) {
            if (backdropBitmap[i] != null && !backdropBitmap[i].isRecycled()) {
                backdropBitmap[i].recycle();
            }
            backdropBitmap[i] = null;
            backdropRes[i] = 0;
        }
    }

    // ----------------------------------------------------------------------- touch

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!measured) return true;
        float x = event.getX() / layout.scale;
        float y = event.getY() / layout.scale;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                dragging = false;
                scrolling = false;
                lastScrollY = y;
                pressedIndex = hits.hit(x, y);
                if (pressedIndex != HitMap.NONE) {
                    dragging = screens[current].onPressDown(
                            hits.idAt(pressedIndex), hits.dataAt(pressedIndex), x, y);
                }
                startClock();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging) {
                    screens[current].onDrag(x, y);
                    invalidate();
                    return true;
                }
                float movedY = y - lastScrollY;
                if (!scrolling && Math.hypot(x - downX, y - downY) > TOUCH_SLOP) {
                    // A drag that is mostly vertical becomes a scroll; either way the
                    // press is cancelled so a control does not fire on release.
                    scrolling = Math.abs(y - downY) > Math.abs(x - downX);
                    pressedIndex = HitMap.NONE;
                }
                if (scrolling && screens[current].onScroll(-movedY)) {
                    lastScrollY = y;
                    requestLayoutPass();
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                pressedIndex = HitMap.NONE;
                dragging = false;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (dragging) {
                    screens[current].onDrag(x, y);
                    dragging = false;
                    pressedIndex = HitMap.NONE;
                    invalidate();
                    return true;
                }
                int released = hits.hit(x, y);
                if (released != HitMap.NONE && released == pressedIndex && !scrolling) {
                    int id = hits.idAt(released);
                    int data = hits.dataAt(released);
                    pressedIndex = HitMap.NONE;
                    // One place, so every control in the app answers a touch. The screen
                    // says which sound -- or none, where it makes its own.
                    int tap = screens[current].tapSound(id, data);
                    if (tap >= 0) activity.playUi(tap);
                    // No FLAG_IGNORE_GLOBAL_SETTING: somebody who has turned haptics
                    // off on the device has turned them off here too.
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    screens[current].onRegion(id, data);
                } else {
                    pressedIndex = HitMap.NONE;
                }
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    /** How hard the given region is being pressed, 0..1, for the press-down treatment. */
    float pressOn(int id) {
        if (pressedIndex == HitMap.NONE) return 0f;
        return hits.idAt(pressedIndex) == id ? pressAmount : 0f;
    }

    /** As {@link #pressOn}, but also matching the region's data value. */
    float pressOn(int id, int data) {
        if (pressedIndex == HitMap.NONE) return 0f;
        return hits.idAt(pressedIndex) == id && hits.dataAt(pressedIndex) == data
               ? pressAmount : 0f;
    }

    boolean onBackPressed() {
        return screens[current].onBack();
    }

    // -------------------------------------------------------------- engine callbacks

    @Override public void onTaskCompleted(int index, boolean last) {
        cheerRemaining = CHEER_SECONDS;
        activity.playBuddySound(buddy().index);
        // And then, a beat later, what to do next. Sequential rather than on top of the
        // buddy's own sound, which is the cue for what was just finished.
        if (!last) postDelayed(this::announceActiveTask, 620L);
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        requestLayoutPass();
        startClock();
    }

    @Override public void onMissionComplete(long remainingMs) {
        // Sound and confetti belong to the state change, not to a draw call. The old
        // build fired the victory music as a side effect inside onDraw.
        // The goal opens first and the fanfare follows it. Fired together they were one
        // muddy noise; a beat apart they read as cause and effect.
        activity.playCue(Sounds.CUE_GOAL);
        final int who = buddy().index;
        postDelayed(() -> activity.playVictory(who), 420L);
        if (pref("confetti", true)) {
            // The opening volley, fired here on the COUNTDOWN screen 1.2s before the
            // route, and still in the air when the celebration screen appears -- the
            // pool is not cleared in between. So it has to be the volley the morning's
            // celebration actually wants, or the handover shows a seam: paper confetti
            // for a beat and then a firework display.
            ScreenComplete.paletteFor(buddy(), palette);
            ScreenComplete.openVolley(particles, Celebration.modeFor(engine.routeSeed()),
                                      layout.play, palette);
        }
        postDelayed(this::celebrate, 1200L);
    }

    @Override public void onTimeUp() {
        activity.playBuddySound(buddy().index);
        startClock();
        // Running out of time ends the morning, the same as finishing does. It used to
        // leave the child on the countdown with an empty clock and "Time is up. Finish
        // your tasks!", waiting for a tap on a button that was the only way off the
        // screen. The beat matches onMissionComplete's, so the buddy's own sound lands
        // before the screen changes either way.
        postDelayed(this::celebrate, 1200L);
    }

    /**
     * Move to the celebration, if we are still on the countdown.
     *
     * <p>Guarded because both ways out of a morning post it on a delay, and in between a
     * parent can have unlocked and gone somewhere else. Routing on top of that would
     * yank them back.
     */
    private void celebrate() {
        if (current == SCREEN_ADVENTURE) route(SCREEN_COMPLETE);
    }

    // ---------------------------------------------------------------------- actions

    void startMorning() {
        engine.start(durationSeconds());
        route(SCREEN_ADVENTURE);
        rebuildScene();
        activity.startKidMode();
        postDelayed(this::announceActiveTask, 700L);
    }

    /**
     * Plays the cue for whatever task is active now.
     *
     * <p>The point of these is a child who cannot yet read the name on the card: a
     * toothbrush sound says brush your teeth in a way "Brush Teeth" does not.
     */
    private void announceActiveTask() {
        if (!attached || engine.allDone() || engine.taskCount() == 0) return;
        int index = Math.min(engine.activeIndex(), engine.taskCount() - 1);
        activity.playActivity(Art.activityKind(engine.taskKey(index)));
    }

    void resetRoutine() {
        engine.reset();
        particles.clear();
        cheerRemaining = 0f;
        blend.snap(Anim.SLEEPY);
        requestLayoutPass();
        invalidate();
    }

    float cheerRemaining() { return cheerRemaining; }
}
