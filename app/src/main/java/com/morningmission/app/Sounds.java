package com.morningmission.app;

/**
 * Every sound that is not a buddy's own, as tables rather than scattered literals.
 *
 * <p>The buddies' tap and eating sounds live on {@link BuddyTheme}, beside the rest of
 * what varies per character. These are the ones that belong to the app: the interface,
 * the routine tasks, the clock, and the reactions to a poke.
 *
 * <p>Here rather than inside {@code MainActivity} for one reason: {@link #ACTIVITY} has
 * to stay exactly as long as {@code Art.ACT_COUNT}, in the same order, and a table that
 * has quietly fallen a row short is the defect this project keeps finding. MainActivity
 * is an Activity and cannot be reached off-device; this class holds nothing but ints, so
 * tools/SelfTest.java can check the lengths line up.
 */
final class Sounds {

    private Sounds() {}

    // ------------------------------------------------------------------ interface

    /** A soft pock. Every chip, row and card in the app. */
    static final int UI_TAP = 0;
    /** Two notes up a fourth. The primary button on a screen, and nothing else. */
    static final int UI_CONFIRM = 1;
    /** A page turning, on a change of screen. */
    static final int UI_PAGE = 2;
    static final int UI_COUNT = 3;

    static final int[] UI = {
        R.raw.ui_tap, R.raw.ui_confirm, R.raw.ui_page,
    };

    // ------------------------------------------------------------ clock and goal

    /** The goal opening at the end of the lane. */
    static final int CUE_GOAL = 0;
    /** Halfway through the morning. */
    static final int CUE_MILESTONE = 1;
    /** One per second through the last ten. */
    static final int CUE_TICK = 2;
    static final int CUE_COUNT = 3;

    static final int[] CUE = {
        R.raw.cue_goal, R.raw.cue_milestone, R.raw.cue_tick,
    };

    // ---------------------------------------------------------------- reactions

    /** The second poke in a run. */
    static final int POKE_GIGGLE = 0;
    /** A rubber-toy squeak, for a poke that lands while the buddy is eating. */
    static final int POKE_SQUEAK = 1;
    /** The third poke, when the buddy commits to its whole signature move. */
    static final int POKE_SPIN = 2;
    static final int POKE_COUNT = 3;

    static final int[] POKE = {
        R.raw.poke_giggle, R.raw.poke_squeak, R.raw.poke_spin,
    };

    // --------------------------------------------------------------- activities

    /**
     * One per {@code Art.ACT_*}, in that order.
     *
     * <p>Played when a task becomes the ACTIVE one rather than when it is ticked off.
     * Completion already plays the buddy's own sound, and two cues on one event is mud;
     * making this the cue for what to do next is also more use to a child who cannot yet
     * read the name on the card.
     */
    static final int[] ACTIVITY = {
        R.raw.act_wake,     // ACT_WAKE     -- an alarm
        R.raw.act_bath,     // ACT_BATH     -- a flush
        R.raw.act_dress,    // ACT_DRESS    -- a zip
        R.raw.act_eat,      // ACT_EAT      -- a spoon on china, then cereal
        R.raw.act_brush,    // ACT_BRUSH    -- brushing teeth
        R.raw.act_hair,     // ACT_HAIR     -- a comb
        R.raw.act_wash,     // ACT_WASH     -- a tap running
        R.raw.act_shoes,    // ACT_SHOES    -- two footfalls
        R.raw.act_pack,     // ACT_PACK     -- a buckle
        R.raw.act_jacket,   // ACT_JACKET   -- fabric and a press-stud
        R.raw.act_pet,      // ACT_PET      -- a collar bell
        R.raw.act_vitamin,  // ACT_VITAMIN  -- a pill bottle shaken
    };
}
