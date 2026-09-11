package com.morningmission.app;

import android.graphics.RectF;

/**
 * The tappable regions of the current screen.
 *
 * <p>Screens register regions from {@code layout()} using the very rectangles they will
 * draw, so a control can no longer be drawn in one place and tapped in another. In the
 * old build the draw code and {@code onTouchEvent} each carried their own copy of the
 * coordinates and had already drifted apart: task rows were drawn at a pitch of 115 and
 * hit-tested at 120, the Edit chip was half-covered by the timer's touch region, and the
 * pause button had no touch region at all.
 *
 * <p>Regions are pre-allocated and reused, so re-registering on every layout costs
 * nothing. Later registrations win, matching paint order: whatever is drawn on top is
 * tapped first.
 */
final class HitMap {

    /** Returned by {@link #hit} when nothing is under the point. */
    static final int NONE = -1;

    private static final int CAPACITY = 48;

    private final RectF[] rects = new RectF[CAPACITY];
    private final int[] ids = new int[CAPACITY];
    private final int[] data = new int[CAPACITY];
    private final boolean[] enabled = new boolean[CAPACITY];
    private int count;

    HitMap() {
        for (int i = 0; i < CAPACITY; i++) rects[i] = new RectF();
    }

    void clear() { count = 0; }

    int size() { return count; }

    /** Registers a region. {@code data} carries an index, e.g. which task row. */
    void add(int id, RectF bounds, int data) {
        if (count >= CAPACITY) return;      // silently ignored; SelfTest asserts headroom
        rects[count].set(bounds);
        ids[count] = id;
        this.data[count] = data;
        enabled[count] = true;
        count++;
    }

    void add(int id, RectF bounds) { add(id, bounds, 0); }

    /**
     * Registers a region grown to at least {@code minSide} on each axis, keeping it
     * centred. Small controls stay visually small while remaining comfortable to tap --
     * the forgiveness the old code applied ad hoc by hit-testing circles at a larger
     * radius than it drew them.
     */
    void addPadded(int id, RectF bounds, float minSide, int data) {
        if (count >= CAPACITY) return;
        float halfW = Math.max(bounds.width(), minSide) * 0.5f;
        float halfH = Math.max(bounds.height(), minSide) * 0.5f;
        float cx = bounds.centerX(), cy = bounds.centerY();
        rects[count].set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        ids[count] = id;
        this.data[count] = data;
        enabled[count] = true;
        count++;
    }

    /**
     * Registers a region clipped to {@code clip}, dropping it entirely if nothing is
     * visible. Used for rows scrolled out of their viewport, which must not stay
     * tappable once off-screen.
     */
    void addClipped(int id, RectF bounds, RectF clip, int data) {
        float top = Math.max(bounds.top, clip.top);
        float bottom = Math.min(bounds.bottom, clip.bottom);
        if (bottom - top < bounds.height() * 0.5f) return;
        if (count >= CAPACITY) return;
        rects[count].set(bounds.left, top, bounds.right, bottom);
        ids[count] = id;
        this.data[count] = data;
        enabled[count] = true;
        count++;
    }

    /** Index of the topmost region containing the point, or {@link #NONE}. */
    int hit(float x, float y) {
        for (int i = count - 1; i >= 0; i--) {
            if (enabled[i] && rects[i].contains(x, y)) return i;
        }
        return NONE;
    }

    int idAt(int index) { return index < 0 || index >= count ? NONE : ids[index]; }

    int dataAt(int index) { return index < 0 || index >= count ? 0 : data[index]; }

    RectF rectAt(int index) { return rects[index]; }

    /** True when {@code id} is registered on the current screen. */
    boolean contains(int id) {
        for (int i = 0; i < count; i++) if (ids[i] == id) return true;
        return false;
    }

    int capacity() { return CAPACITY; }
}
