package com.morningmission.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Editing the morning routine: reorder by dragging a handle, tap a row to rename it or
 * change its activity, add a task, save.
 *
 * <p>Replaces a stock dialog containing a vertical stack of system buttons.
 */
final class ScreenEditRoutine extends Screen {

    private static final int R_BACK = 1, R_ROW = 2, R_HANDLE = 3, R_ADD = 4, R_SAVE = 5;

    private final RectF scratch = new RectF();
    private final java.util.ArrayList<String> names = new java.util.ArrayList<>();
    private final java.util.ArrayList<String> keys = new java.util.ArrayList<>();

    private float scroll;
    private int draggingRow = -1;
    private float dragY;

    ScreenEditRoutine(MorningView view) {
        super(view);
    }

    @Override void onEnter() {
        names.clear();
        keys.clear();
        Engine engine = view.engine;
        for (int i = 0; i < engine.taskCount(); i++) {
            names.add(engine.taskName(i));
            keys.add(engine.taskKey(i));
        }
        draggingRow = -1;
        scroll = 0f;
    }

    /** The editor works on its own copy, so cancelling leaves the routine untouched. */
    int rowCount() { return names.size(); }

    @Override void layout(Layout layout, HitMap hits) {
        layout.scrollEditor(scroll);
        hits.addPadded(R_BACK, layout.edBack, layout.minTouchUnits(), 0);
        int rows = Math.min(names.size(), layout.edRowCount);
        for (int i = 0; i < rows; i++) {
            RectF row = layout.edRow[i];
            scratch.set(row.left, row.top, row.left + row.height(), row.bottom);
            hits.addClipped(R_HANDLE, scratch, layout.edBand, i);
            scratch.set(row.left + row.height(), row.top, row.right, row.bottom);
            hits.addClipped(R_ROW, scratch, layout.edBand, i);
        }
        hits.add(R_ADD, layout.edAdd);
        hits.add(R_SAVE, layout.edSave);
    }

    @Override void draw(Canvas c, Layout layout, float t, float dt) {
        BuddyTheme theme = view.buddy();
        view.scene.drawBackground(c, theme, t);
        view.scene.drawForeground(c, theme, t, false);

        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setColor(0xE8F4F8FD);
        c.drawRect(0f, 0f, Layout.W, layout.height, fill);

        Icons.glyphChip(c, Art.GLYPH_CHEVRON_LEFT, layout.edBack, 0xFFFFFFFF,
                        theme.ink, view.pressOn(R_BACK));
        Theme.textCentered(c, "Edit Routine", layout.edTitle.centerX(),
                           layout.edTitle.centerY(), Theme.H1, Theme.INK,
                           Paint.Align.CENTER, true);

        c.save();
        c.clipRect(layout.edBand);
        int rows = Math.min(names.size(), layout.edRowCount);
        for (int i = 0; i < rows; i++) {
            if (i == draggingRow) continue;
            drawRow(c, layout.edRow[i], layout.edBand, theme, i);
        }
        if (draggingRow >= 0 && draggingRow < rows) {
            float height = Layout.TASK_ROW_H;
            scratch.set(layout.edBand.left, dragY - height * 0.5f,
                        layout.edBand.right, dragY + height * 0.5f);
            drawRow(c, scratch, layout.edBand, theme, draggingRow);
        }
        c.restore();

        if (names.isEmpty()) {
            Theme.textCentered(c, "No tasks yet. Add the first one below.",
                               layout.edBand.centerX(), layout.edBand.centerY(),
                               Theme.T2, Theme.INK_MUTED, Paint.Align.CENTER, false);
        }

        RectF add = layout.edAdd;
        float addPress = view.pressOn(R_ADD);
        Theme.button(c, add, add.height() * 0.5f, 0xFFFFFFFF, 0xFFDCE6F2, addPress);
        float glyph = add.height() * 0.36f;
        float textWidth = Theme.measure("Add a Task", Theme.T2, true);
        float startX = add.centerX() - (glyph + 16f + textWidth) * 0.5f;
        float cy = add.centerY() + add.height() * 0.04f * addPress;
        Icons.glyph(c, Art.GLYPH_PLUS, startX + glyph * 0.5f, cy, glyph, theme.primary);
        Theme.textCentered(c, "Add a Task", startX + glyph + 16f, cy,
                           Theme.T2, theme.ink, Paint.Align.LEFT, true);

        RectF save = layout.edSave;
        float savePress = view.pressOn(R_SAVE);
        Theme.button(c, save, save.height() * 0.5f, theme.primary,
                     Theme.darken(theme.primary, 0.22f), savePress);
        Theme.textCentered(c, "Save Routine", save.centerX(),
                           save.centerY() + save.height() * 0.04f * savePress,
                           Theme.H2, 0xFFFFFFFF, Paint.Align.CENTER, true);
    }

    private void drawRow(Canvas c, RectF row, RectF clip, BuddyTheme theme, int index) {
        if (row.bottom < clip.top - row.height() || row.top > clip.bottom + row.height()) return;
        boolean lifted = index == draggingRow;
        scratch.set(row);
        if (!lifted) scratch.offset(0f, row.height() * 0.03f * view.pressOn(R_ROW, index));
        Theme.card(c, scratch, scratch.height() * 0.26f,
                   lifted ? Theme.mix(theme.light, 0xFFFFFFFF, 0.1f) : 0xFFFFFFFF);

        float pad = scratch.height() * 0.18f;
        float handle = scratch.height() * 0.30f;
        Icons.glyph(c, Art.GLYPH_DRAG, scratch.left + pad + handle * 0.5f,
                    scratch.centerY(), handle, Theme.INK_FAINT);

        float icon = scratch.height() - pad * 2f;
        float ix = scratch.left + pad * 2f + handle + icon * 0.5f;
        Icons.activityChip(c, Art.activityKind(keys.get(index)), theme,
                           ix, scratch.centerY(), icon, false);

        float chevron = scratch.height() * 0.24f;
        scratch.set(ix + icon * 0.5f + pad, row.top + pad * 0.4f,
                    row.right - pad - chevron, row.centerY() + row.height() * 0.04f);
        Theme.fitText(c, names.get(index), scratch, Theme.T2, 16f, Theme.INK,
                      Paint.Align.LEFT, true);
        scratch.set(ix + icon * 0.5f + pad, row.centerY() + row.height() * 0.06f,
                    row.right - pad - chevron, row.bottom - pad * 0.4f);
        Theme.fitText(c, Art.ACTIVITY_SUBTITLES[Art.activityKind(keys.get(index))],
                      scratch, Theme.B2, 13f, Theme.INK_MUTED, Paint.Align.LEFT, false);
        Icons.glyph(c, Art.GLYPH_CHEVRON_RIGHT, row.right - pad - chevron * 0.5f,
                    row.centerY(), chevron, Theme.INK_FAINT);
    }

    @Override boolean onPressDown(int id, int data, float x, float y) {
        if (id != R_HANDLE) return false;
        draggingRow = data;
        dragY = y;
        return true;
    }

    @Override void onDrag(float x, float y) {
        if (draggingRow < 0) return;
        dragY = y;
        int target = Math.round((y - view.layout.edBand.top + scroll) / Layout.TASK_ROW_PITCH);
        target = Math.max(0, Math.min(names.size() - 1, target));
        if (target != draggingRow) {
            names.add(target, names.remove(draggingRow));
            keys.add(target, keys.remove(draggingRow));
            draggingRow = target;
        }
    }

    @Override boolean onScroll(float dy) {
        if (draggingRow >= 0) return false;
        if (view.layout.maxEditorScroll() <= 0f) return false;
        scroll = view.layout.scrollEditor(scroll + dy);
        return true;
    }

    @Override void onRegion(int id, int data) {
        switch (id) {
            case R_BACK:
                view.route(MorningView.SCREEN_GROWN_UPS);
                break;
            case R_HANDLE:
                draggingRow = -1;
                break;
            case R_ROW:
                view.activity.editTask(this, data);
                break;
            case R_ADD:
                view.activity.addTask(this);
                break;
            case R_SAVE:
                view.activity.saveRoutine(names.toArray(new String[0]),
                                          keys.toArray(new String[0]));
                view.reloadRoutine();
                view.resetRoutine();
                view.route(MorningView.SCREEN_GROWN_UPS);
                break;
            default:
                break;
        }
    }

    // Called back from the activity's dialogs.

    void applyTask(int index, String name, String key) {
        if (index < 0 || index >= names.size()) return;
        names.set(index, name);
        keys.set(index, key);
        view.requestLayoutPass();
        view.invalidate();
    }

    void removeTask(int index) {
        if (index < 0 || index >= names.size()) return;
        names.remove(index);
        keys.remove(index);
        view.requestLayoutPass();
        view.invalidate();
    }

    void addTask(String name, String key) {
        if (names.size() >= Layout.MAX_TASK_ROWS) {
            view.activity.toast("That is as many tasks as a morning can hold.");
            return;
        }
        names.add(name);
        keys.add(key);
        view.requestLayoutPass();
        view.invalidate();
    }

    String nameAt(int index) { return index >= 0 && index < names.size() ? names.get(index) : ""; }

    String keyAt(int index) { return index >= 0 && index < keys.size() ? keys.get(index) : "DRESS"; }

    @Override boolean onBack() {
        view.route(MorningView.SCREEN_GROWN_UPS);
        return true;
    }
}
