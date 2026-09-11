package com.morningmission.app;

/**
 * Every shape the app draws in code, as plain {@code float[]} command arrays in a
 * 100x100 unit box.
 *
 * <p>Authoring geometry as data rather than as {@code Path} constants is deliberate.
 * {@code android.graphics.Path} is native-backed and throws off-device, so a Path-based
 * icon set could never be checked outside an emulator. As data it has one source of truth
 * and three consumers: {@link Clay#compile} builds the real Paths on the device,
 * tools/SelfTest.java bounds-checks every shape under a plain JVM, and the same numbers
 * can be rendered for a design preview.
 *
 * <p>This class imports nothing. Keep it that way.
 *
 * <p><b>Drawing conventions.</b> Corners use {@code quad} rather than {@code line}: even
 * the pointed forms -- a shark fin, a chest lid, a star -- get a slight bulge, which is
 * the difference between clipart and something sculpted. Shapes stay inside roughly
 * 6..94 so the rim light in {@link Clay} has room. No shape carries an outline; the
 * modelling passes do that work.
 */
final class Art {

    private Art() {}

    // -------------------------------------------------------------------- opcodes

    static final int MOVE = 0;
    static final int LINE = 1;
    static final int QUAD = 2;
    static final int CUBIC = 3;
    static final int CLOSE = 4;
    static final int OVAL = 5;
    static final int CIRCLE = 6;
    static final int RRECT = 7;
    /** A circle wound the other way, which subtracts from the shape. */
    static final int HOLE = 8;

    /** Number of coordinates each opcode consumes. */
    static int operandCount(int op) {
        switch (op) {
            case MOVE: case LINE: return 2;
            case QUAD: case OVAL: return 4;
            case CUBIC: case RRECT: return 6;
            case CIRCLE: case HOLE: return 3;
            case CLOSE: return 0;
            default: return -1;
        }
    }

    // ------------------------------------------------------------- colour encoding
    //
    // A part's colour is either a literal ARGB value or, when it is below 16, an index
    // into the active buddy's palette. Opaque colours always have a non-zero alpha byte,
    // so small integers are unambiguous as role markers.

    static final int ROLE_PRIMARY = 0;
    static final int ROLE_ACCENT  = 1;
    static final int ROLE_ACCENT2 = 2;
    static final int ROLE_LIGHT   = 3;
    static final int ROLE_DARK    = 4;
    static final int ROLE_INK     = 5;
    static final int ROLE_BODY    = 6;
    static final int ROLE_CHEEK   = 7;

    static boolean isRole(int color) { return (color >>> 24) == 0 && color >= 0 && color < 16; }

    // Natural colours for everyday objects. A shirt stays blue and a toothbrush stays red
    // whichever buddy is chosen: the buddy's colour comes from the tinted chip behind the
    // icon, the way the mockup does it. Tinting the objects themselves gave a blue sun.
    private static final int WHITE   = 0xFFFFFFFF;
    private static final int CREAM   = 0xFFFFF6E6;
    private static final int SUN     = 0xFFFFD84D;
    private static final int SUN_DEEP= 0xFFFFC24A;
    private static final int SKY     = 0xFF7ED6F5;
    private static final int WATER   = 0xFF5BC8F5;
    private static final int RED     = 0xFFE8533F;
    private static final int RED_DEEP= 0xFFC93D2B;
    private static final int BLUE    = 0xFF3E8FDE;
    private static final int BLUE_DEEP=0xFF2568B5;
    private static final int GREEN   = 0xFF4FAE6B;
    private static final int GREEN_DEEP=0xFF35894F;
    private static final int ORANGE  = 0xFFF2A02C;
    private static final int BROWN   = 0xFF9B5A31;
    private static final int BROWN_DEEP=0xFF7A4423;
    private static final int GOLD    = 0xFFFFC857;
    private static final int GREY    = 0xFFC7D2DE;
    private static final int GREY_DEEP=0xFF98A6B8;
    private static final int PINK    = 0xFFF07BA6;
    private static final int LEAF    = 0xFF55BB68;

    // ------------------------------------------------------------- shape builder
    //
    // Native-free, so it runs in the self-test too. Used at class initialisation only.

    static final class B {
        private float[] data = new float[96];
        private int n;

        private void need(int extra) {
            if (n + extra <= data.length) return;
            int size = data.length;
            while (size < n + extra) size <<= 1;
            float[] bigger = new float[size];
            System.arraycopy(data, 0, bigger, 0, n);
            data = bigger;
        }

        B move(float x, float y) { need(3); data[n++] = MOVE; data[n++] = x; data[n++] = y; return this; }

        B line(float x, float y) { need(3); data[n++] = LINE; data[n++] = x; data[n++] = y; return this; }

        B quad(float cx, float cy, float x, float y) {
            need(5);
            data[n++] = QUAD; data[n++] = cx; data[n++] = cy; data[n++] = x; data[n++] = y;
            return this;
        }

        B cubic(float c1x, float c1y, float c2x, float c2y, float x, float y) {
            need(7);
            data[n++] = CUBIC;
            data[n++] = c1x; data[n++] = c1y; data[n++] = c2x; data[n++] = c2y;
            data[n++] = x; data[n++] = y;
            return this;
        }

        B close() { need(1); data[n++] = CLOSE; return this; }

        B circle(float cx, float cy, float r) {
            need(4);
            data[n++] = CIRCLE; data[n++] = cx; data[n++] = cy; data[n++] = r;
            return this;
        }

        /** Subtracts a circle, for a ring or a gear's hub. */
        B hole(float cx, float cy, float r) {
            need(4);
            data[n++] = HOLE; data[n++] = cx; data[n++] = cy; data[n++] = r;
            return this;
        }

        B oval(float l, float t, float r, float b) {
            need(5);
            data[n++] = OVAL; data[n++] = l; data[n++] = t; data[n++] = r; data[n++] = b;
            return this;
        }

        B rrect(float l, float t, float r, float b, float rad) {
            need(7);
            data[n++] = RRECT; data[n++] = l; data[n++] = t; data[n++] = r; data[n++] = b;
            data[n++] = rad; data[n++] = rad;
            return this;
        }

        /** A round-ended bar from one point to another. */
        B capsule(float x0, float y0, float x1, float y1, float width) {
            float dx = x1 - x0, dy = y1 - y0;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-4f) return circle(x0, y0, width * 0.5f);
            float nx = -dy / len * width * 0.5f, ny = dx / len * width * 0.5f;
            float ex = dx / len * width * 0.5f, ey = dy / len * width * 0.5f;
            move(x0 + nx, y0 + ny);
            quad(x0 + nx - ex, y0 + ny - ey, x0 - ex * 1.0f, y0 - ey * 1.0f);
            quad(x0 - nx - ex, y0 - ny - ey, x0 - nx, y0 - ny);
            line(x1 - nx, y1 - ny);
            quad(x1 - nx + ex, y1 - ny + ey, x1 + ex, y1 + ey);
            quad(x1 + nx + ex, y1 + ny + ey, x1 + nx, y1 + ny);
            return close();
        }

        /** A star with softened points, which is what keeps it in the clay family. */
        B star(float cx, float cy, float outer, float inner, int points, float rotationDeg) {
            double step = Math.PI / points;
            double start = Math.toRadians(rotationDeg) - Math.PI / 2;
            for (int i = 0; i < points * 2; i++) {
                double a = start + i * step;
                float r = (i % 2 == 0) ? outer : inner;
                float x = cx + (float) Math.cos(a) * r;
                float y = cy + (float) Math.sin(a) * r;
                double am = a + step * 0.5;
                float rm = (i % 2 == 0) ? outer * 0.86f : inner * 1.06f;
                float mx = cx + (float) Math.cos(am) * rm;
                float my = cy + (float) Math.sin(am) * rm;
                if (i == 0) move(x, y);
                quad(mx, my, cx + (float) Math.cos(a + step) * ((i % 2 == 0) ? inner : outer),
                             cy + (float) Math.sin(a + step) * ((i % 2 == 0) ? inner : outer));
            }
            return close();
        }

        /** Spokes radiating from a centre, as round-ended bars. */
        B rays(float cx, float cy, float inner, float outer, float width, int count, float rotationDeg) {
            for (int i = 0; i < count; i++) {
                double a = Math.toRadians(rotationDeg + i * 360.0 / count);
                capsule(cx + (float) Math.cos(a) * inner, cy + (float) Math.sin(a) * inner,
                        cx + (float) Math.cos(a) * outer, cy + (float) Math.sin(a) * outer,
                        width);
            }
            return this;
        }

        /** A teardrop with its point upward: a honey drop, a water drop. */
        B drop(float cx, float cy, float w, float h) {
            move(cx, cy - h * 0.5f);
            cubic(cx + w * 0.42f, cy - h * 0.12f, cx + w * 0.5f, cy + h * 0.16f, cx + w * 0.5f, cy + h * 0.24f);
            cubic(cx + w * 0.5f, cy + h * 0.5f, cx - w * 0.5f, cy + h * 0.5f, cx - w * 0.5f, cy + h * 0.24f);
            cubic(cx - w * 0.5f, cy + h * 0.16f, cx - w * 0.42f, cy - h * 0.12f, cx, cy - h * 0.5f);
            return close();
        }

        /** A heart, used for the kitty treat and the reward markers. */
        B heart(float cx, float cy, float w, float h) {
            move(cx, cy + h * 0.46f);
            cubic(cx - w * 0.62f, cy + h * 0.06f, cx - w * 0.50f, cy - h * 0.44f, cx, cy - h * 0.12f);
            cubic(cx + w * 0.50f, cy - h * 0.44f, cx + w * 0.62f, cy + h * 0.06f, cx, cy + h * 0.46f);
            return close();
        }

        float[] build() {
            float[] out = new float[n];
            System.arraycopy(data, 0, out, 0, n);
            return out;
        }
    }

    private static B b() { return new B(); }

    // ------------------------------------------------------------- activity icons

    static final int ACT_WAKE = 0, ACT_BATH = 1, ACT_DRESS = 2, ACT_EAT = 3,
                     ACT_BRUSH = 4, ACT_HAIR = 5, ACT_WASH = 6, ACT_SHOES = 7,
                     ACT_PACK = 8, ACT_JACKET = 9, ACT_PET = 10, ACT_VITAMIN = 11,
                     ACT_COUNT = 12;

    /** The task keys the routine editor stores, in the same order as the icon kinds. */
    static final String[] ACTIVITY_KEYS = {
        "WAKE", "BATH", "DRESS", "EAT", "BRUSH", "HAIR",
        "WASH", "SHOES", "PACK", "JACKET", "PET", "VITAMIN"
    };

    /** Friendly names, matching the presets offered in the routine editor. */
    static final String[] ACTIVITY_NAMES = {
        "Wake Up", "Use Bathroom", "Get Dressed", "Breakfast", "Brush Teeth", "Brush Hair",
        "Wash Face", "Shoes On", "Backpack", "Jacket", "Feed Pet", "Vitamins"
    };

    /** The encouraging line under each task name. */
    static final String[] ACTIVITY_SUBTITLES = {
        "Rise and shine!", "Bathroom break", "Ready for the day!", "Fuel up!",
        "Sparkly and clean!", "Looking great!", "Fresh face!", "Almost there!",
        "Ready for adventure!", "Warm and ready!", "Help your buddy!", "Healthy start!"
    };

    /** Resolves a stored task key to an icon kind, defaulting to Get Dressed. */
    static int activityKind(String key) {
        if (key != null) {
            for (int i = 0; i < ACTIVITY_KEYS.length; i++) {
                if (ACTIVITY_KEYS[i].equals(key)) return i;
            }
        }
        return ACT_DRESS;
    }

    static final float[][][] ACTIVITY_SHAPES = new float[ACT_COUNT][][];
    static final int[][] ACTIVITY_COLORS = new int[ACT_COUNT][];
    static final int[][] ACTIVITY_FLAGS = new int[ACT_COUNT][];

    private static void activity(int kind, float[][] shapes, int[] colors, int[] flags) {
        ACTIVITY_SHAPES[kind] = shapes;
        ACTIVITY_COLORS[kind] = colors;
        ACTIVITY_FLAGS[kind] = flags;
    }

    static {
        // Wake Up -- a sun clearing a small cloud.
        activity(ACT_WAKE,
            new float[][]{
                b().rays(50, 46, 27, 41, 9, 8, 22).build(),
                b().circle(50, 46, 24).build(),
                b().move(24, 76).quad(24, 64, 36, 64).quad(40, 55, 50, 58)
                   .quad(62, 56, 64, 66).quad(78, 64, 78, 76).quad(78, 84, 68, 84)
                   .line(34, 84).quad(24, 84, 24, 76).close().build(),
            },
            new int[]{SUN_DEEP, SUN, WHITE},
            new int[]{Clay.MODEL, Clay.GLOSSY, Clay.SOLID});

        // Use Bathroom -- a basin with a tap and a drop.
        activity(ACT_BATH,
            new float[][]{
                b().capsule(64, 20, 64, 40, 9).capsule(48, 24, 66, 24, 9).build(),
                b().move(18, 52).quad(18, 46, 26, 46).line(82, 46).quad(90, 46, 88, 54)
                   .quad(84, 82, 53, 84).quad(22, 82, 18, 52).close().build(),
                b().drop(64, 55, 13, 17).build(),
            },
            new int[]{GREY, WHITE, WATER},
            new int[]{Clay.MODEL, Clay.SOLID, Clay.GLOSSY});

        // Get Dressed -- a striped tee, as in the mockup.
        activity(ACT_DRESS,
            new float[][]{
                b().move(30, 26).quad(40, 22, 50, 24).quad(60, 22, 70, 26)
                   .line(88, 38).quad(92, 41, 89, 46).line(80, 58).quad(77, 62, 74, 58)
                   .line(74, 78).quad(74, 84, 68, 84).line(32, 84).quad(26, 84, 26, 78)
                   .line(26, 58).quad(23, 62, 20, 58).line(11, 46).quad(8, 41, 12, 38)
                   .close().build(),
                b().rrect(28, 58, 72, 65, 3).rrect(28, 70, 72, 77, 3).build(),
                b().move(38, 24).quad(50, 36, 62, 24).quad(50, 30, 38, 24).close().build(),
            },
            new int[]{BLUE, WHITE, WHITE},
            new int[]{Clay.SOLID, Clay.FLAT, Clay.FLAT});

        // Breakfast -- a cereal bowl with a spoon.
        activity(ACT_EAT,
            new float[][]{
                b().capsule(74, 24, 84, 62, 7).oval(66, 16, 86, 34).build(),
                b().move(14, 46).quad(50, 38, 86, 46).quad(84, 80, 50, 84)
                   .quad(16, 80, 14, 46).close().build(),
                b().move(18, 48).quad(50, 42, 82, 48).quad(70, 58, 50, 58)
                   .quad(30, 58, 18, 48).close().build(),
                b().circle(33, 50, 6).circle(50, 47, 6).circle(66, 51, 6).build(),
            },
            new int[]{GREY, BLUE, CREAM, ORANGE},
            new int[]{Clay.MODEL, Clay.SOLID, Clay.FLAT, Clay.MODEL});

        // Brush Teeth -- a toothbrush with a ribbon of paste.
        activity(ACT_BRUSH,
            new float[][]{
                b().capsule(24, 76, 70, 30, 15).build(),
                b().capsule(66, 34, 84, 16, 17).build(),
                b().capsule(70, 30, 82, 18, 8).build(),
                b().move(60, 42).quad(52, 50, 44, 48).quad(50, 42, 58, 38).close().build(),
            },
            new int[]{RED, WHITE, RED_DEEP, SKY},
            new int[]{Clay.SOLID, Clay.SOLID, Clay.FLAT, Clay.MODEL});

        // Brush Hair -- a hairbrush.
        activity(ACT_HAIR,
            new float[][]{
                b().capsule(30, 72, 76, 26, 20).build(),
                b().capsule(20, 84, 36, 66, 13).build(),
                b().capsule(38, 58, 48, 48, 6).capsule(50, 70, 60, 60, 6)
                   .capsule(50, 46, 60, 36, 6).capsule(62, 58, 72, 48, 6).build(),
            },
            new int[]{ORANGE, BROWN, WHITE},
            new int[]{Clay.SOLID, Clay.MODEL, Clay.FLAT});

        // Wash Face -- a big drop with a sparkle.
        activity(ACT_WASH,
            new float[][]{
                b().drop(46, 54, 52, 62).build(),
                b().star(74, 30, 16, 6, 4, 0).build(),
                b().drop(78, 66, 16, 20).build(),
            },
            new int[]{WATER, WHITE, SKY},
            new int[]{Clay.GLOSSY, Clay.FLAT, Clay.MODEL});

        // Shoes On -- a sneaker.
        activity(ACT_SHOES,
            new float[][]{
                b().move(14, 66).quad(14, 44, 32, 44).quad(44, 44, 52, 52)
                   .quad(64, 62, 84, 64).quad(92, 65, 92, 72).line(92, 74)
                   .quad(92, 80, 84, 80).line(22, 80).quad(14, 80, 14, 66).close().build(),
                b().move(12, 76).quad(12, 70, 20, 70).line(86, 70).quad(94, 70, 94, 78)
                   .quad(94, 86, 86, 86).line(20, 86).quad(12, 86, 12, 76).close().build(),
                b().capsule(30, 50, 44, 58, 5).capsule(30, 60, 44, 52, 5).build(),
            },
            new int[]{RED, WHITE, WHITE},
            new int[]{Clay.SOLID, Clay.SOLID, Clay.FLAT});

        // Backpack -- with a star, as in the mockup.
        activity(ACT_PACK,
            new float[][]{
                b().capsule(34, 30, 34, 48, 9).capsule(66, 30, 66, 48, 9).build(),
                b().move(18, 48).quad(18, 30, 50, 30).quad(82, 30, 82, 48)
                   .line(82, 78).quad(82, 88, 72, 88).line(28, 88).quad(18, 88, 18, 78)
                   .close().build(),
                b().move(18, 50).quad(18, 36, 50, 36).quad(82, 36, 82, 50)
                   .quad(82, 62, 50, 62).quad(18, 62, 18, 50).close().build(),
                b().star(50, 74, 13, 6, 5, 0).build(),
            },
            new int[]{BLUE_DEEP, BLUE, BLUE_DEEP, GOLD},
            new int[]{Clay.MODEL, Clay.SOLID, Clay.MODEL, Clay.GLOSSY});

        // Jacket -- with a hood and a zip.
        activity(ACT_JACKET,
            new float[][]{
                b().move(26, 32).quad(38, 24, 50, 26).quad(62, 24, 74, 32)
                   .line(88, 44).quad(92, 48, 88, 52).line(78, 60).quad(75, 63, 73, 59)
                   .line(73, 82).quad(73, 88, 66, 88).line(34, 88).quad(27, 88, 27, 82)
                   .line(27, 59).quad(25, 63, 22, 60).line(12, 52).quad(8, 48, 12, 44)
                   .close().build(),
                b().move(34, 28).quad(50, 16, 66, 28).quad(50, 34, 34, 28).close().build(),
                b().rrect(46, 34, 54, 86, 4).build(),
            },
            new int[]{GREEN, GREEN_DEEP, CREAM},
            new int[]{Clay.SOLID, Clay.MODEL, Clay.FLAT});

        // Feed Pet -- a paw print.
        activity(ACT_PET,
            new float[][]{
                b().move(26, 62).quad(26, 48, 50, 48).quad(74, 48, 74, 62)
                   .quad(74, 82, 50, 82).quad(26, 82, 26, 62).close().build(),
                b().oval(18, 26, 34, 46).oval(38, 18, 54, 40).oval(58, 20, 74, 42)
                   .oval(76, 34, 90, 52).build(),
            },
            new int[]{ORANGE, ORANGE},
            new int[]{Clay.SOLID, Clay.MODEL});

        // Vitamins -- a two-tone capsule with a sparkle.
        activity(ACT_VITAMIN,
            new float[][]{
                b().capsule(30, 70, 70, 30, 34).build(),
                b().move(38, 78).quad(18, 62, 34, 42).quad(52, 60, 38, 78).close().build(),
                b().star(76, 26, 15, 6, 4, 0).build(),
            },
            new int[]{SUN, RED, WHITE},
            new int[]{Clay.GLOSSY, Clay.MODEL, Clay.FLAT});
    }

    // ---------------------------------------------------------------- collectibles
    // Indexed by buddy. Drawn small -- as little as 24 units across with twelve on
    // screen -- so each is two or three parts at most.

    static final float[][][] COLLECTIBLE_SHAPES = new float[BuddyThemeCount.N][][];
    static final int[][] COLLECTIBLE_COLORS = new int[BuddyThemeCount.N][];
    static final int[][] COLLECTIBLE_FLAGS = new int[BuddyThemeCount.N][];

    /** Kept separate from BuddyTheme so this class imports nothing at all. */
    static final class BuddyThemeCount { static final int N = 7; private BuddyThemeCount() {} }

    private static void collectible(int buddy, float[][] shapes, int[] colors, int[] flags) {
        COLLECTIBLE_SHAPES[buddy] = shapes;
        COLLECTIBLE_COLORS[buddy] = colors;
        COLLECTIBLE_FLAGS[buddy] = flags;
    }

    static {
        // Burger -- a mini burger.
        collectible(0,
            new float[][]{
                b().move(14, 44).quad(14, 18, 50, 18).quad(86, 18, 86, 44).close().build(),
                b().move(14, 46).quad(50, 40, 86, 46).quad(70, 56, 50, 54)
                   .quad(30, 56, 14, 46).close().build(),
                b().rrect(14, 52, 86, 68, 7).build(),
                b().move(14, 68).quad(50, 62, 86, 68).quad(86, 86, 50, 86)
                   .quad(14, 86, 14, 68).close().build(),
            },
            new int[]{SUN_DEEP, LEAF, BROWN, SUN},
            new int[]{Clay.GLOSSY, Clay.FLAT, Clay.MODEL, Clay.SOLID});

        // Bee -- a honey drop.
        collectible(1,
            new float[][]{ b().drop(50, 52, 60, 74).build() },
            new int[]{0xFFFFC945},
            new int[]{Clay.GLOSSY});

        // Pug -- a bone.
        collectible(2,
            new float[][]{
                b().rrect(22, 40, 78, 60, 10).build(),
                b().circle(24, 38, 15).circle(24, 62, 15).circle(76, 38, 15).circle(76, 62, 15).build(),
            },
            new int[]{0xFFFFF5DC, 0xFFFFF5DC},
            new int[]{Clay.MODEL, Clay.SOLID});

        // Shark -- a fish.
        collectible(3,
            new float[][]{
                b().move(16, 50).quad(32, 22, 62, 32).quad(80, 40, 80, 50)
                   .quad(80, 60, 62, 68).quad(32, 78, 16, 50).close().build(),
                b().move(74, 50).quad(86, 34, 94, 28).quad(92, 50, 94, 72)
                   .quad(86, 66, 74, 50).close().build(),
                b().circle(34, 44, 7).build(),
            },
            new int[]{0xFFFF8A32, 0xFFF07421, WHITE},
            new int[]{Clay.SOLID, Clay.MODEL, Clay.FLAT});

        // Dino -- a leaf.
        collectible(4,
            new float[][]{
                b().move(20, 76).quad(16, 34, 54, 20).quad(84, 30, 80, 58)
                   .quad(70, 84, 20, 76).close().build(),
                b().capsule(24, 78, 68, 36, 6).build(),
            },
            new int[]{LEAF, 0xFF3E9A52},
            new int[]{Clay.SOLID, Clay.FLAT});

        // Cloud -- a star.
        collectible(5,
            new float[][]{ b().star(50, 50, 42, 18, 5, 0).build() },
            new int[]{0xFFFFD84D},
            new int[]{Clay.GLOSSY});

        // Kitty -- a heart-shaped treat.
        collectible(6,
            new float[][]{
                b().heart(50, 50, 74, 70).build(),
                b().circle(38, 40, 6).build(),
            },
            new int[]{0xFFFF75A5, WHITE},
            new int[]{Clay.GLOSSY, Clay.FLAT});
    }

    // ------------------------------------------------------------------------ goals
    // The destination at the end of the trail. Drawn large, so these carry more parts.

    static final float[][][] GOAL_SHAPES = new float[BuddyThemeCount.N][][];
    static final int[][] GOAL_COLORS = new int[BuddyThemeCount.N][];
    static final int[][] GOAL_FLAGS = new int[BuddyThemeCount.N][];

    /** Per goal, the index of the part that opens on completion, or -1. */
    static final int[] GOAL_LID = new int[BuddyThemeCount.N];

    static final String[] GOAL_NAMES = {
        "Picnic", "Hive", "Doghouse", "Treasure", "Nest", "Rainbow", "Present"
    };

    private static void goal(int buddy, float[][] shapes, int[] colors, int[] flags, int lid) {
        GOAL_SHAPES[buddy] = shapes;
        GOAL_COLORS[buddy] = colors;
        GOAL_FLAGS[buddy] = flags;
        GOAL_LID[buddy] = lid;
    }

    static {
        // Picnic basket.
        goal(0,
            new float[][]{
                b().move(20, 48).quad(50, 40, 80, 48).quad(76, 88, 50, 88)
                   .quad(24, 88, 20, 48).close().build(),
                b().move(28, 50).quad(50, 20, 72, 50).quad(64, 50, 62, 44)
                   .quad(50, 30, 38, 44).quad(36, 50, 28, 50).close().build(),
                b().rrect(18, 44, 82, 58, 6).build(),
                b().capsule(34, 48, 34, 86, 5).capsule(50, 44, 50, 88, 5)
                   .capsule(66, 48, 66, 86, 5).build(),
            },
            new int[]{BROWN, BROWN_DEEP, RED, BROWN_DEEP},
            new int[]{Clay.SOLID, Clay.MODEL, Clay.MODEL, Clay.FLAT}, 1);

        // Golden hive.
        goal(1,
            new float[][]{
                b().move(22, 84).quad(18, 40, 50, 22).quad(82, 40, 78, 84).close().build(),
                b().capsule(28, 44, 72, 44, 8).capsule(24, 60, 76, 60, 8)
                   .capsule(26, 76, 74, 76, 8).build(),
                b().oval(40, 60, 60, 80).build(),
            },
            new int[]{GOLD, 0xFFE0A32B, BROWN_DEEP},
            new int[]{Clay.GLOSSY, Clay.FLAT, Clay.MODEL}, -1);

        // Doghouse.
        goal(2,
            new float[][]{
                b().move(16, 88).line(16, 48).quad(16, 44, 20, 44).line(80, 44)
                   .quad(84, 44, 84, 48).line(84, 88).close().build(),
                b().move(8, 48).quad(50, 8, 92, 48).quad(84, 52, 76, 48)
                   .quad(50, 22, 24, 48).quad(16, 52, 8, 48).close().build(),
                b().move(36, 88).quad(36, 56, 50, 56).quad(64, 56, 64, 88).close().build(),
            },
            new int[]{0xFFF1C073, RED, BROWN_DEEP},
            new int[]{Clay.SOLID, Clay.MODEL, Clay.MODEL}, 1);

        // Treasure chest.
        goal(3,
            new float[][]{
                b().rrect(14, 48, 86, 88, 8).build(),
                b().move(14, 50).quad(14, 20, 50, 20).quad(86, 20, 86, 50)
                   .quad(50, 44, 14, 50).close().build(),
                b().rrect(42, 44, 58, 68, 4).build(),
                b().star(50, 34, 11, 5, 5, 0).build(),
            },
            new int[]{BROWN, 0xFFC97F3E, GOLD, SUN},
            new int[]{Clay.SOLID, Clay.MODEL, Clay.MODEL, Clay.GLOSSY}, 1);

        // Dino nest with eggs.
        goal(4,
            new float[][]{
                b().move(10, 66).quad(50, 52, 90, 66).quad(88, 88, 50, 88)
                   .quad(12, 88, 10, 66).close().build(),
                b().oval(24, 38, 50, 72).oval(52, 44, 76, 74).build(),
                b().circle(32, 54, 4).circle(42, 62, 4).circle(62, 58, 4).build(),
                b().capsule(12, 68, 40, 62, 5).capsule(60, 62, 88, 70, 5).build(),
            },
            new int[]{BROWN, CREAM, LEAF, BROWN_DEEP},
            new int[]{Clay.SOLID, Clay.GLOSSY, Clay.FLAT, Clay.FLAT}, -1);

        // Rainbow over a cloud.
        goal(5,
            new float[][]{
                b().move(6, 74).quad(6, 12, 50, 12).quad(94, 12, 94, 74)
                   .line(80, 74).quad(80, 26, 50, 26).quad(20, 26, 20, 74).close().build(),
                b().move(20, 74).quad(20, 34, 50, 34).quad(80, 34, 80, 74)
                   .line(66, 74).quad(66, 48, 50, 48).quad(34, 48, 34, 74).close().build(),
                b().move(34, 74).quad(34, 56, 50, 56).quad(66, 56, 66, 74).close().build(),
                b().move(10, 92).quad(6, 76, 22, 74).quad(28, 62, 42, 68)
                   .quad(56, 60, 64, 72).quad(84, 70, 88, 82).quad(94, 92, 78, 92)
                   .close().build(),
            },
            new int[]{RED, SUN, 0xFF5BC8F5, WHITE},
            new int[]{Clay.MODEL, Clay.MODEL, Clay.MODEL, Clay.SOLID}, -1);

        // Gift box.
        goal(6,
            new float[][]{
                b().rrect(16, 44, 84, 88, 8).build(),
                b().rrect(10, 30, 90, 50, 7).build(),
                b().rrect(43, 30, 57, 88, 5).build(),
                b().move(50, 32).quad(24, 30, 30, 14).quad(44, 8, 50, 30)
                   .quad(56, 8, 70, 14).quad(76, 30, 50, 32).close().build(),
            },
            new int[]{PINK, 0xFFE85E92, WHITE, 0xFFE85E92},
            new int[]{Clay.SOLID, Clay.MODEL, Clay.FLAT, Clay.GLOSSY}, 1);
    }

    // -------------------------------------------------------------------- UI glyphs
    // Single-colour filled paths replacing the emoji the old build drew as text. Emoji
    // render differently on every manufacturer's build, and three of the characters used
    // were not emoji at all but rare Unicode that shows as an empty box on some devices.

    static final int GLYPH_GEAR = 0, GLYPH_CHEVRON_LEFT = 1, GLYPH_CHEVRON_RIGHT = 2,
                     GLYPH_PAUSE = 3, GLYPH_CHECK = 4, GLYPH_STAR = 5, GLYPH_PLAY = 6,
                     GLYPH_CLOSE = 7, GLYPH_PENCIL = 8, GLYPH_HOME = 9, GLYPH_LIST = 10,
                     GLYPH_PEOPLE = 11, GLYPH_LOCK = 12, GLYPH_DRAG = 13, GLYPH_PLUS = 14,
                     GLYPH_SPEAKER = 15, GLYPH_REFRESH = 16, GLYPH_CROWN = 17,
                     GLYPH_HEART = 18, GLYPH_MINUS = 19, GLYPH_COUNT = 20;

    static final float[][] GLYPHS = new float[GLYPH_COUNT][];

    static {
        // Blunt, stubby teeth around a thick ring. Longer, thinner spokes read as an
        // asterisk rather than a gear, which is what the first version of this did.
        // A capsule extends half its width past each endpoint, so teeth reach 36 + 13.
        GLYPHS[GLYPH_GEAR] = b()
            .rays(50, 50, 30, 36, 26, 8, 22)
            .circle(50, 50, 34)
            .hole(50, 50, 14)
            .build();

        GLYPHS[GLYPH_CHEVRON_LEFT] = b()
            .capsule(62, 22, 34, 50, 15).capsule(34, 50, 62, 78, 15).build();

        GLYPHS[GLYPH_CHEVRON_RIGHT] = b()
            .capsule(38, 22, 66, 50, 15).capsule(66, 50, 38, 78, 15).build();

        GLYPHS[GLYPH_PAUSE] = b()
            .rrect(26, 20, 44, 80, 8).rrect(56, 20, 74, 80, 8).build();

        GLYPHS[GLYPH_CHECK] = b()
            .capsule(22, 52, 42, 71, 16).capsule(42, 71, 79, 30, 16).build();

        GLYPHS[GLYPH_STAR] = b().star(50, 52, 44, 19, 5, 0).build();

        GLYPHS[GLYPH_PLAY] = b()
            .move(30, 20).quad(30, 12, 38, 17).line(80, 44)
            .quad(87, 50, 80, 56).line(38, 83).quad(30, 88, 30, 80).close().build();

        GLYPHS[GLYPH_CLOSE] = b()
            .capsule(26, 26, 74, 74, 15).capsule(74, 26, 26, 74, 15).build();

        GLYPHS[GLYPH_PENCIL] = b()
            .move(18, 82).quad(16, 84, 17, 76).line(21, 60).line(40, 79).line(24, 83)
            .quad(18, 84, 18, 82).close()
            .move(27, 55).line(55, 27).line(74, 46).line(46, 74).close()
            .move(61, 21).quad(68, 14, 75, 21).line(80, 26).quad(87, 33, 80, 40)
            .line(74, 46).line(55, 27).close().build();

        GLYPHS[GLYPH_HOME] = b()
            .move(50, 14).quad(53, 12, 56, 15).line(88, 44).quad(92, 48, 86, 51)
            .line(80, 51).line(80, 82).quad(80, 88, 74, 88).line(60, 88).line(60, 62)
            .quad(60, 58, 56, 58).line(44, 58).quad(40, 58, 40, 62).line(40, 88)
            .line(26, 88).quad(20, 88, 20, 82).line(20, 51).line(14, 51)
            .quad(8, 48, 12, 44).close().build();

        GLYPHS[GLYPH_LIST] = b()
            .circle(22, 28, 8).circle(22, 50, 8).circle(22, 72, 8)
            .rrect(38, 22, 84, 34, 6).rrect(38, 44, 84, 56, 6).rrect(38, 66, 84, 78, 6).build();

        GLYPHS[GLYPH_PEOPLE] = b()
            .circle(35, 34, 17).circle(69, 38, 13)
            .move(10, 82).quad(10, 56, 35, 56).quad(60, 56, 60, 82).close()
            .move(56, 82).quad(56, 62, 69, 62).quad(90, 62, 90, 82).close().build();

        GLYPHS[GLYPH_LOCK] = b()
            .move(28, 48).line(28, 36).quad(28, 14, 50, 14).quad(72, 14, 72, 36)
            .line(72, 48).line(60, 48).line(60, 36).quad(60, 26, 50, 26)
            .quad(40, 26, 40, 36).line(40, 48).close()
            .rrect(20, 46, 80, 88, 12).build();

        GLYPHS[GLYPH_DRAG] = b()
            .rrect(24, 30, 76, 38, 4).rrect(24, 46, 76, 54, 4).rrect(24, 62, 76, 70, 4).build();

        GLYPHS[GLYPH_PLUS] = b()
            .capsule(22, 50, 78, 50, 16).capsule(50, 22, 50, 78, 16).build();

        GLYPHS[GLYPH_MINUS] = b().capsule(22, 50, 78, 50, 16).build();

        GLYPHS[GLYPH_SPEAKER] = b()
            .move(16, 38).line(34, 38).line(52, 20).quad(58, 15, 58, 24).line(58, 76)
            .quad(58, 85, 52, 80).line(34, 62).line(16, 62).quad(10, 62, 10, 56)
            .line(10, 44).quad(10, 38, 16, 38).close()
            .move(70, 34).quad(84, 50, 70, 66).quad(78, 50, 70, 34).close()
            .move(80, 22).quad(100, 50, 80, 78).quad(90, 50, 80, 22).close().build();

        GLYPHS[GLYPH_REFRESH] = b()
            .move(50, 14).quad(86, 14, 86, 50).quad(86, 86, 50, 86)
            .quad(24, 86, 16, 64).quad(28, 60, 32, 68).quad(38, 74, 50, 74)
            .quad(74, 74, 74, 50).quad(74, 26, 50, 26).close()
            .move(52, 6).line(52, 34).quad(52, 40, 46, 36).line(22, 22)
            .quad(16, 19, 22, 16).line(46, 4).quad(52, 1, 52, 6).close().build();

        GLYPHS[GLYPH_CROWN] = b()
            .move(14, 74).line(10, 30).quad(9, 22, 16, 27).line(34, 42).line(45, 20)
            .quad(50, 11, 55, 20).line(66, 42).line(84, 27).quad(91, 22, 90, 30)
            .line(86, 74).quad(86, 80, 78, 80).line(22, 80).quad(14, 80, 14, 74)
            .close().build();

        GLYPHS[GLYPH_HEART] = b().heart(50, 52, 80, 74).build();
    }
}
