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
    private static final int SCREEN_COUNT = 7;

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
    private int current = SCREEN_HOME;
    private int previous = SCREEN_HOME;
    private float routeProgress = 1f;

    private final Anim.Blend blend = new Anim.Blend();
    /** Solved separately so a collectible action can be composed onto the walk. */
    private final Anim.Transform feastMotion = new Anim.Transform();
    private final Anim.Transform motion = new Anim.Transform();
    private final int[] palette = new int[6];
    private final RectF scratch = new RectF();

    // Buddy artwork, loaded on demand. The selected buddy is decoded at full size; the
    // others are halved, since they are only ever seen at picker size.
    private final Bitmap[] art = new Bitmap[BuddyTheme.COUNT];
    private final int[] artSample = new int[BuddyTheme.COUNT];
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
        activity.setTitleMusic(current == SCREEN_HOME);
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
        Bitmap bitmap = art(buddyIndex, buddyIndex == buddy().index);
        if (bitmap == null || bitmap.isRecycled()) return;

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
        float width = height * bitmap.getWidth() / (float) bitmap.getHeight();
        float centreY = feetY - height * 0.5f + motion.dy;

        if (withShadow) {
            float squash = Theme.clamp(motion.scaleY, 0.85f, 1.15f);
            Clay.contactShadow(c, cx + motion.dx, feetY + height * 0.02f,
                               width * 0.36f * (2f - squash), height * 0.055f,
                               Theme.clamp(1f - Math.abs(motion.dy) / 90f, 0.25f, 1f));
        }

        c.save();
        c.translate(cx + motion.dx, centreY);
        c.rotate(motion.rotation);
        c.scale(motion.scaleX, motion.scaleY, 0f, height * 0.42f);
        scratch.set(-width * 0.5f, -height * 0.5f, width * 0.5f, height * 0.5f);
        Theme.BMP.setAlpha(255);
        c.drawBitmap(bitmap, null, scratch, Theme.BMP);
        c.restore();
    }

    /** Draws a buddy with a fixed pose, for the picker where seven dance at once. */
    void drawBuddyPose(Canvas c, int buddyIndex, float cx, float feetY, float height,
                       int state, float phaseOffset) {
        Bitmap bitmap = art(buddyIndex, false);
        if (bitmap == null || bitmap.isRecycled()) return;
        Anim.solve(state, BuddyTheme.of(buddyIndex).temperament, time + phaseOffset, motion);
        float width = height * bitmap.getWidth() / (float) bitmap.getHeight();
        c.save();
        c.translate(cx + motion.dx, feetY - height * 0.5f + motion.dy);
        c.rotate(motion.rotation);
        c.scale(motion.scaleX, motion.scaleY, 0f, height * 0.42f);
        scratch.set(-width * 0.5f, -height * 0.5f, width * 0.5f, height * 0.5f);
        Theme.BMP.setAlpha(255);
        c.drawBitmap(bitmap, null, scratch, Theme.BMP);
        c.restore();
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
            particles.celebrate(layout.play, buddy(), palette);
        }
        postDelayed(() -> route(SCREEN_COMPLETE), 1200L);
    }

    @Override public void onTimeUp() {
        activity.playBuddySound(buddy().index);
        startClock();
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
