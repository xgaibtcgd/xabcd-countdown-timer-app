package com.morningmission.app;

/**
 * Everything that varies between the buddies, in one table.
 *
 * <p>This replaces ten parallel arrays and switch statements that were keyed by a bare
 * {@code prefs.getInt("buddy", 0)} and scattered across the old single-file build --
 * names, art ids, sound ids, sound words, collectible nouns, and the collectible and
 * goal drawing switches. Four of those sites indexed their array without a bounds
 * check, so a stale preference would throw; {@link #of(int)} clamps once for all of
 * them.
 *
 * <p>The palettes are sampled from the actual buddy PNGs, so anything drawn in code --
 * scenery, icons, collectibles, goals, buttons -- can be tinted to the chosen buddy and
 * still look like it belongs with the artwork. Before this, every accent in the app was
 * one of three hardcoded literals shared by every character.
 *
 * <p>Index order is load-bearing: it is the value stored in SharedPreferences under
 * "buddy" by every previously shipped version, so it must stay
 * burger, bee, pug, shark, dino, cloud, kitty. A new character goes on the END of the
 * list, never in the middle, or every child in the world wakes up to a different animal.
 */
final class BuddyTheme {

    /** Cheek blush shared by every character; the common highlight of the family. */
    static final int CHEEK = 0xFFEA5A70;

    final int index;
    /** Stable identifier for logs and tests; not shown to the user. */
    final String key;

    String name, soundWord, munchWord, collectOne, collectMany;
    /** Which {@link Anim} FEAST_* motion this buddy performs at a collectible. */
    int feastKind = Anim.FEAST_BITE;
    /** Which {@link Anim} SIG_* move it performs when poked, and as a cheer flourish. */
    int signatureKind = Anim.SIG_HOP;
    /**
     * How this character carries itself through every one of the seventeen states.
     *
     * <p>Before this the whole cast shared one body: the bee sat on the ground bobbing
     * at exactly the rate the triceratops did. See {@link Anim.Temperament}.
     */
    Anim.Temperament temperament = Anim.PLAIN;
    int artRes, soundRes;
    /**
     * The same character with its arms up, for the celebration.
     *
     * <p>Fitted to {@code artRes}'s own framing rather than to its own bounding box: a
     * cheer is a different shape from a stand, so normalising by bounds would have made
     * every character change size the moment it celebrated. The placement was solved by
     * maximising silhouette overlap against the walking sprite, which lands the head on
     * the head and the body on the body whatever the limbs are doing.
     */
    int cheerRes;
    /** Played once per bite as this buddy eats a treat. Never the same as soundRes. */
    int eatRes;
    /** This character's Mission Complete fanfare. Eight styles, one each. */
    int victoryRes;
    /** The illustrated world this buddy's adventure happens in. */
    int backdropRes;

    /**
     * Five parts instead of one flat bitmap, or null for a character not yet rigged.
     *
     * <p>Nullable on purpose: rigging is being rolled out one character at a time, and
     * a half-rigged cast has to keep working. Everything that draws a buddy falls back
     * to {@link #artRes} when this is null. See {@link Rig}.
     */
    Rig rig;

    /** The character's signature colour. Drives scenery, accents and buttons. */
    int primary;
    /** Secondary colour, for the accent element of an icon. */
    int accent;
    /** Third colour, used sparingly -- a wing, a stripe, a bow. */
    int accent2;
    /**
     * Very light tint of the character, for card fills and scene washes.
     *
     * <p>Shark and cloud are both hue ~203, so their tints are separated by saturation
     * rather than hue -- shark's stays a visibly aqua wash, cloud's is nearly white.
     * Sampled straight from the art they were almost the same colour, which made their
     * cards indistinguishable side by side in the picker.
     */
    int light;
    /** Near-neutral body colour (cream fur, a bun). Card fills only, never a theme colour. */
    int body;
    /** Readable dark, derived from the primary hue. Use for text and chevrons. */
    int ink;
    /** The character's own dark punctuation (a stripe, a nose, a paw). Use in icon detail. */
    int dark;

    private BuddyTheme(int index, String key) {
        this.index = index;
        this.key = key;
    }

    private BuddyTheme words(String name, String soundWord, String munchWord,
                             String collectOne, String collectMany) {
        this.name = name;
        this.soundWord = soundWord;
        this.munchWord = munchWord;
        this.collectOne = collectOne;
        this.collectMany = collectMany;
        return this;
    }

    private BuddyTheme feast(int kind) {
        this.feastKind = kind;
        return this;
    }

    private BuddyTheme moves(int signatureKind, float tempo, float bounce, float sway,
                             float tilt, float squash, float hover) {
        this.signatureKind = signatureKind;
        this.temperament = new Anim.Temperament(tempo, bounce, sway, tilt, squash, hover);
        return this;
    }

    /** Gives this character a rig. Only the bee has one so far. */
    private BuddyTheme rigged(Rig rig) {
        this.rig = rig;
        return this;
    }

    private BuddyTheme assets(int artRes, int cheerRes, int soundRes, int eatRes,
                              int victoryRes, int backdropRes) {
        this.artRes = artRes;
        this.cheerRes = cheerRes;
        this.soundRes = soundRes;
        this.eatRes = eatRes;
        this.victoryRes = victoryRes;
        this.backdropRes = backdropRes;
        return this;
    }

    private BuddyTheme palette(int primary, int accent, int accent2,
                               int light, int body, int ink, int dark) {
        this.primary = primary;
        this.accent = accent;
        this.accent2 = accent2;
        this.light = light;
        this.body = body;
        this.ink = ink;
        this.dark = dark;
        return this;
    }

    static final BuddyTheme[] ALL = {
        new BuddyTheme(0, "burger")
            .words("Burger Buddy", "nom!", "YUM!", "mini burger", "mini burgers")
            .feast(Anim.FEAST_BITE)
            .moves(Anim.SIG_HOP, 1.10f, 1.05f, 0.95f, 1.05f, 1.10f, 0f)
            .assets(R.drawable.buddy_burger, R.drawable.buddy_burger_cheer,
                    R.raw.buddy_burger_sound, R.raw.buddy_burger_eat,
                    R.raw.victory_burger, R.drawable.bg_adventure_burger)
            .rigged(Rig.BURGER)
            .palette(0xFFF39B28, 0xFF2772C9, 0xFFEC4777, 0xFFFDEDD8, 0xFFFBF4EA, 0xFF8A4A12, 0xFFC7B8B9),

        new BuddyTheme(1, "bee")
            .words("Queen Bee", "buzz!", "BUZZ!", "honey drop", "honey drops")
            .feast(Anim.FEAST_SIP)
            .moves(Anim.SIG_FLUTTER, 1.30f, 0.70f, 1.30f, 0.80f, 0.50f, 1.0f)
            .assets(R.drawable.buddy_bee, R.drawable.buddy_bee_cheer,
                    R.raw.buddy_bee_sound, R.raw.buddy_bee_eat,
                    R.raw.victory_bee, R.drawable.bg_adventure_bee)
            .rigged(Rig.BEE)
            .palette(0xFFFBD638, 0xFFEAA815, 0xFF8DCCF7, 0xFFFEF8DB, 0xFFFFFDF0, 0xFF6B4E05, 0xFF271A17),

        new BuddyTheme(2, "pug")
            .words("Pug Pal", "ruff!", "NOM!", "bone", "bones")
            .feast(Anim.FEAST_POUNCE)
            .moves(Anim.SIG_PRANCE, 1.15f, 1.35f, 0.90f, 1.20f, 1.30f, 0f)
            .assets(R.drawable.buddy_pug, R.drawable.buddy_pug_cheer,
                    R.raw.buddy_pug_sound, R.raw.buddy_pug_eat,
                    R.raw.victory_pug, R.drawable.bg_adventure_pug)
            .rigged(Rig.PUG)
            .palette(0xFFFA6801, 0xFFDF8542, 0xFFF9D6A8, 0xFFFEE4D1, 0xFFF9EFE4, 0xFF7A3300, 0xFF382826),

        new BuddyTheme(3, "shark")
            .words("Splash Buddy", "splash!", "CHOMP!", "fish", "fish")
            .feast(Anim.FEAST_LUNGE)
            .moves(Anim.SIG_ROLL, 0.85f, 0.60f, 1.50f, 1.40f, 0.70f, 0.55f)
            .assets(R.drawable.buddy_shark, R.drawable.buddy_shark_cheer,
                    R.raw.buddy_shark_sound, R.raw.buddy_shark_eat,
                    R.raw.victory_shark, R.drawable.bg_adventure_shark)
            .rigged(Rig.SHARK)
            .palette(0xFF25A7F9, 0xFF0776D9, 0xFFAE3242, 0xFFD3ECFE, 0xFFF6F7F9, 0xFF0B4D8F, 0xFF246FC8),

        new BuddyTheme(4, "dino")
            .words("Sprout Dino", "rawr!", "MUNCH!", "leaf", "leaves")
            .feast(Anim.FEAST_STOMP)
            .moves(Anim.SIG_SPRING, 1.05f, 1.30f, 1.00f, 0.95f, 1.25f, 0f)
            .assets(R.drawable.buddy_dino, R.drawable.buddy_dino_cheer,
                    R.raw.buddy_dino_sound, R.raw.buddy_dino_eat,
                    R.raw.victory_dino, R.drawable.bg_adventure_dino)
            .rigged(Rig.DINO)
            .palette(0xFF67CFA4, 0xFF259474, 0xFF83D126, 0xFFE4F6EF, 0xFFF2FBF7, 0xFF1B6B52, 0xFF49B893),

        new BuddyTheme(5, "cloud")
            .words("Cloud Pup", "ding!", "SPARKLE!", "star", "stars")
            .feast(Anim.FEAST_SPIN)
            .moves(Anim.SIG_PUFF, 0.80f, 0.90f, 1.40f, 0.90f, 0.60f, 0.8f)
            .assets(R.drawable.buddy_cloud, R.drawable.buddy_cloud_cheer,
                    R.raw.buddy_cloud_sound, R.raw.buddy_cloud_eat,
                    R.raw.victory_cloud, R.drawable.bg_adventure_cloud)
            .rigged(Rig.CLOUD)
            .palette(0xFF57B9F3, 0xFF3798E4, 0xFFFFFFFF, 0xFFF0F7FF, 0xFFF5FBFF, 0xFF1D6FA8, 0xFF45A8EB),

        new BuddyTheme(6, "kitty")
            .words("Sweet Kitty", "meow!", "PURR!", "heart", "hearts")
            .feast(Anim.FEAST_NIBBLE)
            .moves(Anim.SIG_WIGGLE, 1.20f, 0.80f, 0.80f, 1.10f, 0.90f, 0f)
            .assets(R.drawable.buddy_kitty, R.drawable.buddy_kitty_cheer,
                    R.raw.buddy_kitty_sound, R.raw.buddy_kitty_eat,
                    R.raw.victory_kitty, R.drawable.bg_adventure_kitty)
            .rigged(Rig.KITTY)
            .palette(0xFFF85798, 0xFFD03D6B, 0xFFFFFFFF, 0xFFFEE1EC, 0xFFF8ECE2, 0xFFA32354, 0xFFD4BBB7),

        new BuddyTheme(7, "trike")
            .words("Jungle Trike", "snort!", "CRUNCH!", "melon slice", "melon slices")
            .feast(Anim.FEAST_TOSS)
            .moves(Anim.SIG_STOMP, 0.80f, 1.25f, 0.70f, 0.70f, 1.45f, 0f)
            .assets(R.drawable.buddy_trike, R.drawable.buddy_trike_cheer,
                    R.raw.buddy_trike_sound, R.raw.buddy_trike_eat,
                    R.raw.victory_trike, R.drawable.bg_adventure_trike)
            .rigged(Rig.TRIKE)
            .palette(0xFFA6CC63, 0xFF39944F, 0xFFCE1B2C, 0xFFEEF7D9, 0xFFF2EAE0, 0xFF3C6B22, 0xFF24783C),
    };

    static final int COUNT = ALL.length;

    /** Never throws: an out-of-range stored preference falls back to the first buddy. */
    static BuddyTheme of(int index) {
        return (index < 0 || index >= ALL.length) ? ALL[0] : ALL[index];
    }

    /** Clamps a stored preference into range, for writing back. */
    static int clampIndex(int index) {
        return (index < 0 || index >= ALL.length) ? 0 : index;
    }

    /**
     * Phrase for the collectible counter, e.g. "1 fish" / "4 fish". Shark's collectible
     * has no distinct plural, which is why singular and plural are stored separately
     * rather than derived by appending an "s".
     */
    String collectibleNoun(int count) {
        return count == 1 ? collectOne : collectMany;
    }

    @Override public String toString() {
        return "BuddyTheme(" + index + "," + key + ")";
    }
}
