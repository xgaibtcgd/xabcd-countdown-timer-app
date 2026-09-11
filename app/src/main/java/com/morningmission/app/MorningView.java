package com.morningmission.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Choreographer;
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
    private final Anim.Transform motion = new Anim.Transform();
    private final int[] palette = new int[6];
    private final RectF scratch = new RectF();

    // Buddy artwork, loaded on demand. The selected buddy is decoded at full size; the
    // others are halved, since they are only ever seen at picker size.
    private final Bitmap[] art = new Bitmap[BuddyTheme.COUNT];
    private final int[] artSample = new int[BuddyTheme.COUNT];

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

    int minutes() {
        return Math.max(1, Math.min(120, activity.prefs.getInt("minutes", 15)));
    }

    void setMinutes(int value) {
        int safe = Math.max(1, Math.min(120, value));
        activity.prefs.edit().putInt("minutes", safe).apply();
        engine.setDurationMinutes(safe);
        invalidate();
    }

    boolean pref(String key, boolean fallback) {
        return activity.prefs.getBoolean(key, fallback);
    }

    void setPref(String key, boolean value) {
        activity.prefs.edit().putBoolean(key, value).apply();
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
        requestLayoutPass();
        rebuildScene();
        screens[current].onEnter();
        startClock();
        invalidate();
    }

    /** Re-runs the current screen's layout and re-registers its hit regions. */
    void requestLayoutPass() {
        if (getWidth() == 0 || getHeight() == 0) return;
        layout.measure(getWidth(), getHeight(), insetTopPx, insetBottomPx,
                       getResources().getDisplayMetrics().densityDpi,
                       engine.taskCount());
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
        scene.rebuild(layout.play, buddy(), mode);
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
        stroke.setStyle(Paint.Style.FILL);
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
        Bitmap bitmap = art(buddyIndex, buddyIndex == buddy().index);
        if (bitmap == null || bitmap.isRecycled()) return;

        // Called once per frame, which is what lets the blend track velocity for squash
        // and stretch. drawBuddyPose below solves without touching that state, so the
        // seven dancing buddies in the picker cannot disturb it.
        blend.solve(time, lastDelta, motion);
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
        Anim.solve(state, time + phaseOffset, motion);
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

    private void releaseArt() {
        for (int i = 0; i < art.length; i++) {
            if (art[i] != null && !art[i].isRecycled()) art[i].recycle();
            art[i] = null;
            artSample[i] = 0;
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
        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        requestLayoutPass();
        startClock();
    }

    @Override public void onMissionComplete(long remainingMs) {
        // Sound and confetti belong to the state change, not to a draw call. The old
        // build fired the victory music as a side effect inside onDraw.
        activity.playVictory();
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
        engine.start(minutes());
        route(SCREEN_ADVENTURE);
        rebuildScene();
        activity.startKidMode();
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
