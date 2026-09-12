package com.morningmission.app;

import java.io.FileWriter;
import java.io.IOException;

/**
 * Dumps the shape data and palettes as JSON, for the design preview in tools/preview/.
 *
 * <p>Runs under the same plain JVM as the self-test, because {@link Art} and
 * {@link BuddyTheme} deliberately import nothing native. The preview therefore renders
 * the same numbers the app compiles into Paths, rather than a hand-copied approximation
 * that could drift.
 *
 * <p>Usage: java com.morningmission.app.ExportArt tools/preview/art.json
 */
public final class ExportArt {

    public static void main(String[] args) throws IOException {
        String out = args.length > 0 ? args[0] : "tools/preview/art.json";
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("{\n");

        sb.append("\"roles\":[\"primary\",\"accent\",\"accent2\",\"light\",\"dark\",")
          .append("\"ink\",\"body\",\"cheek\"],\n");

        sb.append("\"buddies\":[");
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            BuddyTheme b = BuddyTheme.ALL[i];
            if (i > 0) sb.append(",");
            sb.append("\n{\"index\":").append(b.index)
              .append(",\"key\":").append(str(b.key))
              .append(",\"name\":").append(str(b.name))
              .append(",\"soundWord\":").append(str(b.soundWord))
              .append(",\"collectMany\":").append(str(b.collectMany))
              .append(",\"munchWord\":").append(str(b.munchWord))
              .append(",\"feastKind\":").append(b.feastKind)
              .append(",\"goalName\":").append(str(Art.GOAL_NAMES[i]))
              .append(",\"primary\":").append(hex(b.primary))
              .append(",\"accent\":").append(hex(b.accent))
              .append(",\"accent2\":").append(hex(b.accent2))
              .append(",\"light\":").append(hex(b.light))
              .append(",\"dark\":").append(hex(b.dark))
              .append(",\"ink\":").append(hex(b.ink))
              .append(",\"body\":").append(hex(b.body))
              .append("}");
        }
        sb.append("\n],\n");

        sb.append("\"activities\":[");
        for (int i = 0; i < Art.ACT_COUNT; i++) {
            if (i > 0) sb.append(",");
            sb.append("\n{\"key\":").append(str(Art.ACTIVITY_KEYS[i]))
              .append(",\"name\":").append(str(Art.ACTIVITY_NAMES[i]))
              .append(",\"subtitle\":").append(str(Art.ACTIVITY_SUBTITLES[i]))
              .append(",").append(parts(Art.ACTIVITY_SHAPES[i], Art.ACTIVITY_COLORS[i],
                                        Art.ACTIVITY_FLAGS[i]))
              .append("}");
        }
        sb.append("\n],\n");

        // The bite circles come from Art, so the preview cuts in exactly the places the
        // app does rather than carrying its own copy of the geometry. It subtracts them
        // by clipping, since Canvas2D has no boolean path operations.
        sb.append("\"collectibles\":[");
        float[] bounds = new float[4];
        float[] circle = new float[3];
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            if (i > 0) sb.append(",");
            sb.append("\n{").append(parts(Art.COLLECTIBLE_SHAPES[i], Art.COLLECTIBLE_COLORS[i],
                                          Art.COLLECTIBLE_FLAGS[i]));
            Art.shapeBounds(Art.COLLECTIBLE_SHAPES[i], bounds);
            sb.append(",\"bites\":[");
            for (int b = 0; b < Art.BITE_COUNT - 1; b++) {
                if (b > 0) sb.append(",");
                Art.biteCircle(bounds, b, circle);
                sb.append(floats(circle));
            }
            sb.append("]}");
        }
        sb.append("\n],\n");

        sb.append("\"goals\":[");
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            if (i > 0) sb.append(",");
            sb.append("\n{\"name\":").append(str(Art.GOAL_NAMES[i]))
              .append(",\"lid\":").append(Art.GOAL_LID[i])
              .append(",").append(parts(Art.GOAL_SHAPES[i], Art.GOAL_COLORS[i],
                                        Art.GOAL_FLAGS[i]))
              .append("}");
        }
        sb.append("\n],\n");

        String[] glyphNames = {
            "gear", "chevron-left", "chevron-right", "pause", "check", "star", "play",
            "close", "pencil", "home", "list", "people", "lock", "drag", "plus",
            "speaker", "refresh", "crown", "heart", "minus", "note"
        };
        sb.append("\"glyphs\":[");
        for (int i = 0; i < Art.GLYPH_COUNT; i++) {
            if (i > 0) sb.append(",");
            sb.append("\n{\"name\":").append(str(i < glyphNames.length ? glyphNames[i] : "glyph" + i))
              .append(",\"d\":").append(floats(Art.GLYPHS[i])).append("}");
        }
        sb.append("\n]\n}\n");

        try (FileWriter w = new FileWriter(out)) {
            w.write(sb.toString());
        }
        System.out.println("ExportArt: wrote " + out + " (" + sb.length() + " bytes)");
    }

    private static String parts(float[][] shapes, int[] colors, int[] flags) {
        StringBuilder sb = new StringBuilder();
        sb.append("\"parts\":[");
        for (int i = 0; i < shapes.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"d\":").append(floats(shapes[i]))
              .append(",\"color\":").append(Art.isRole(colors[i]) ? colors[i] : 0)
              .append(",\"role\":").append(Art.isRole(colors[i]))
              .append(",\"hex\":").append(Art.isRole(colors[i]) ? "null" : hex(colors[i]))
              .append(",\"flags\":").append(flags[i]).append("}");
        }
        return sb.append("]").toString();
    }

    private static String floats(float[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            float v = values[i];
            if (v == (long) v) sb.append((long) v);
            else sb.append(Math.round(v * 1000f) / 1000f);
        }
        return sb.append("]").toString();
    }

    /**
     * CSS hex. Eight digits when the colour is not opaque, six when it is.
     *
     * <p>This used to mask the alpha off unconditionally, which meant the one part in
     * the art that is deliberately translucent -- the volcano's smoke plume -- rendered
     * solid in the preview and solid nowhere else. A preview that quietly disagrees with
     * the device on a colour is worse than no preview, since it is the surface the
     * artwork gets judged on. Six digits is still the normal case, so nothing that parses
     * these with a plain {@code slice(1, 7)} changes.
     */
    private static String hex(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        String rgb = String.format("%06X", argb & 0xFFFFFF);
        return alpha == 0xFF ? "\"#" + rgb + "\""
                             : "\"#" + rgb + String.format("%02X", alpha) + "\"";
    }

    private static String str(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
