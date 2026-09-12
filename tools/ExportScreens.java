package com.morningmission.app;

import android.graphics.RectF;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.io.IOException;

/**
 * Dumps the real, solved layout for the design preview.
 *
 * <p>The point is fidelity. Rather than a preview re-deriving where things go and slowly
 * drifting from the app, this runs the app's own {@link Layout} off-device -- it is free
 * of native graphics state precisely so it can -- and exports every named rectangle it
 * produces. Whatever the preview draws sits exactly where the built app puts it.
 *
 * <p>Theme's colour roles and type scale come along too. They are compile-time constants,
 * so reading them here does not load Theme, which does touch native state.
 *
 * <p>Usage: java com.morningmission.app.ExportScreens tools/preview/screens.json
 */
public final class ExportScreens {

    /** Device shapes worth previewing. Name, width, height, density, inset top, bottom. */
    private static final Object[][] DEVICES = {
        {"Modern phone",  1080, 2400, 480, 90, 130},
        {"Tall phone",    1080, 2640, 480, 110, 130},
        {"Older 16:9",    1080, 1920, 480, 72, 48},
        {"Small phone",    720, 1280, 320, 60, 40},
    };

    /** Every named rectangle on Layout, found by reflection so none can be forgotten. */
    private static String rects(Layout layout) throws IllegalAccessException {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Field f : Layout.class.getDeclaredFields()) {
            if (f.isSynthetic()) continue;
            f.setAccessible(true);
            Object value = f.get(layout);
            if (value instanceof RectF) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\n  ").append(str(f.getName())).append(":").append(rect((RectF) value));
            } else if (value instanceof RectF[]) {
                RectF[] array = (RectF[]) value;
                if (!first) sb.append(",");
                first = false;
                sb.append("\n  ").append(str(f.getName())).append(":[");
                for (int i = 0; i < array.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(rect(array[i]));
                }
                sb.append("]");
            }
        }
        sb.append(",\n  \"taskRowCount\":").append(layout.taskRowCount)
          .append(",\n  \"scale\":").append(round(layout.scale))
          .append(",\n  \"height\":").append(round(layout.height))
          .append(",\n  \"minTouch\":").append(round(layout.minTouchUnits()))
          .append(",\n  \"taskContentHeight\":").append(round(layout.taskContentHeight))
          .append(",\n  \"guContentHeight\":").append(round(layout.guContentHeight));
        return sb.append("\n}").toString();
    }

    public static void main(String[] args) throws IOException, IllegalAccessException {
        String out = args.length > 0 ? args[0] : "tools/preview/screens.json";
        int taskCount = 5;
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("{\n");

        sb.append("\"devices\":[");
        for (int d = 0; d < DEVICES.length; d++) {
            Object[] device = DEVICES[d];
            Layout layout = new Layout();
            layout.measure((Integer) device[1], (Integer) device[2],
                           (Integer) device[4], (Integer) device[5],
                           (Integer) device[3], taskCount, taskCount);
            if (d > 0) sb.append(",");
            sb.append("\n{\"name\":").append(str((String) device[0]))
              .append(",\"widthPx\":").append(device[1])
              .append(",\"heightPx\":").append(device[2])
              .append(",\"dpi\":").append(device[3])
              .append(",\"insetTop\":").append(device[4])
              .append(",\"insetBottom\":").append(device[5])
              .append(",\"layout\":").append(rects(layout))
              .append("}");
        }
        sb.append("\n],\n");

        // Colour roles and the type scale, straight from Theme.
        sb.append("\"tokens\":{")
          .append("\"ink\":").append(hex(Theme.INK))
          .append(",\"inkMuted\":").append(hex(Theme.INK_MUTED))
          .append(",\"inkFaint\":").append(hex(Theme.INK_FAINT))
          .append(",\"surface\":").append(hex(Theme.SURFACE))
          .append(",\"surfaceTint\":").append(hex(Theme.SURFACE_TINT))
          .append(",\"scrim\":").append(argb(Theme.SCRIM))
          .append(",\"cta\":").append(hex(Theme.CTA))
          .append(",\"ctaDeep\":").append(hex(Theme.CTA_DEEP))
          .append(",\"success\":").append(hex(Theme.SUCCESS))
          .append(",\"successDeep\":").append(hex(Theme.SUCCESS_DEEP))
          .append(",\"link\":").append(hex(Theme.LINK))
          .append(",\"gold\":").append(hex(Theme.GOLD))
          .append(",\"warn\":").append(hex(Theme.WARN))
          .append("},\n");

        sb.append("\"type\":{")
          .append("\"d1\":").append(Theme.D1).append(",\"d2\":").append(Theme.D2)
          .append(",\"h1\":").append(Theme.H1).append(",\"h2\":").append(Theme.H2)
          .append(",\"t1\":").append(Theme.T1).append(",\"t2\":").append(Theme.T2)
          .append(",\"b1\":").append(Theme.B1).append(",\"b2\":").append(Theme.B2)
          .append(",\"c1\":").append(Theme.C1)
          .append("},\n");

        sb.append("\"metrics\":{")
          .append("\"designWidth\":").append(Layout.W)
          .append(",\"taskRowHeight\":").append(Layout.TASK_ROW_H)
          .append(",\"taskRowPitch\":").append(Layout.TASK_ROW_PITCH)
          .append(",\"chip\":").append(Layout.CHIP)
          .append(",\"navItems\":").append(Layout.NAV_ITEMS)
          .append(",\"grownUpRows\":").append(Layout.GROWN_UP_ROWS)
          .append("},\n");

        // The scenery palettes, one entry per buddy.
        sb.append("\"environments\":[");
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            if (i > 0) sb.append(",");
            sb.append("\n{\"name\":").append(str(Scene.ENVIRONMENT_NAMES[i]))
              .append(",\"skyTop\":").append(hex(Scene.SKY_TOP[i]))
              .append(",\"skyMid\":").append(hex(Scene.SKY_MID[i]))
              .append(",\"skyLow\":").append(hex(Scene.SKY_LOW[i]))
              .append(",\"groundNear\":").append(hex(Scene.GROUND_NEAR[i]))
              .append(",\"groundFar\":").append(hex(Scene.GROUND_FAR[i]))
              .append(",\"horizon\":").append(Scene.HORIZON[i])
              .append(",\"scrim\":").append(Scene.SCRIM[i])
              .append("}");
        }
        sb.append("\n],\n");

        // The default routine, so the preview shows the rows a new install shows.
        sb.append("\"defaultRoutine\":[");
        String[] names = {"Get Dressed", "Breakfast", "Brush Teeth", "Shoes On", "Backpack"};
        String[] keys = {"DRESS", "EAT", "BRUSH", "SHOES", "PACK"};
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(",");
            int kind = Art.activityKind(keys[i]);
            sb.append("{\"name\":").append(str(names[i]))
              .append(",\"kind\":").append(kind)
              .append(",\"subtitle\":").append(str(Art.ACTIVITY_SUBTITLES[kind]))
              .append("}");
        }
        sb.append("],\n");

        sb.append("\"grownUpRows\":[");
        String[] labels = {"Edit Routine", "Choose Buddy", "Sounds", "Music", "Celebration",
                           "Timer Settings", "Kid Lock", "Reset Progress", "About"};
        int[] glyphs = {Art.GLYPH_LIST, Art.GLYPH_HEART, Art.GLYPH_SPEAKER, Art.GLYPH_NOTE,
                        Art.GLYPH_STAR, Art.GLYPH_REFRESH, Art.GLYPH_LOCK, Art.GLYPH_REFRESH,
                        Art.GLYPH_PEOPLE};
        String[] values = {null, "@buddy", "On", "On", "On", "@minutes", "Off", null, null};
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"label\":").append(str(labels[i]))
              .append(",\"glyph\":").append(glyphs[i])
              .append(",\"value\":").append(values[i] == null ? "null" : str(values[i]))
              .append("}");
        }
        sb.append("]\n}\n");

        try (FileWriter w = new FileWriter(out)) {
            w.write(sb.toString());
        }
        System.out.println("ExportScreens: wrote " + out + " (" + sb.length() + " bytes)");
    }

    private static String rect(RectF r) {
        return "[" + round(r.left) + "," + round(r.top) + ","
                   + round(r.right) + "," + round(r.bottom) + "]";
    }

    private static float round(float v) {
        return Math.round(v * 100f) / 100f;
    }

    private static String hex(int argb) {
        return "\"#" + String.format("%06X", argb & 0xFFFFFF) + "\"";
    }

    private static String argb(int value) {
        return "\"rgba(" + ((value >> 16) & 0xFF) + "," + ((value >> 8) & 0xFF) + ","
               + (value & 0xFF) + "," + Math.round(((value >>> 24) / 255f) * 100f) / 100f + ")\"";
    }

    private static String str(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
