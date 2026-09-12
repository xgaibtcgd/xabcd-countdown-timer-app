package com.morningmission.app;

import android.graphics.Canvas;

/**
 * One screen of the app.
 *
 * <p>Each screen places its controls in {@link #layout} and registers those same
 * rectangles with the {@link HitMap}, then draws from them. That is the whole point of
 * the split: in the old build the drawing code and the touch handler each carried their
 * own copy of the coordinates, and they had drifted apart.
 *
 * <p>Registration happens in {@code layout}, never in {@code draw}, so touch is correct
 * before a frame has ever been drawn.
 */
abstract class Screen {

    final MorningView view;

    Screen(MorningView view) {
        this.view = view;
    }

    /** Places controls and registers hit regions. Called on size change and on entry. */
    abstract void layout(Layout layout, HitMap hits);

    /**
     * Draws the screen.
     *
     * @param t seconds since the view was attached
     * @param dt seconds since the previous frame
     */
    abstract void draw(Canvas c, Layout layout, float t, float dt);

    /** Handles a tap on a region this screen registered. */
    abstract void onRegion(int id, int data);

    /**
     * Which interface sound a tap on this region makes.
     *
     * <p>Default {@link Sounds#UI_TAP} -- every control answers a touch unless a screen
     * says otherwise, which is the way round that keeps a new control from shipping
     * silent. Override with {@link Sounds#UI_CONFIRM} for the one primary button on a
     * screen, or -1 where the control already makes a sound of its own and a tick on
     * top of it would only muddy it.
     */
    int tapSound(int id, int data) { return Sounds.UI_TAP; }

    /** True while the screen needs to keep redrawing. */
    boolean animating() { return true; }

    /** Called when the screen becomes visible. */
    void onEnter() {}

    /** A drag in progress, in design units. Only screens with sliders need this. */
    void onDrag(float x, float y) {}

    /** A press that began on a region, for slider capture. Return true to take the drag. */
    boolean onPressDown(int id, int data, float x, float y) { return false; }

    /** Vertical scroll, in design units. Return true if the screen consumed it. */
    boolean onScroll(float dy) { return false; }

    /** Back gesture. Return true if handled. */
    boolean onBack() { return false; }
}
