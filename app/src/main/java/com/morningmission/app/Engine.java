package com.morningmission.app;

/**
 * The routine and the countdown: which task is active, how much time is left, and when
 * the mission is finished.
 *
 * <p>Deliberately free of {@code android.graphics} and of any native-backed class, so
 * that tools/SelfTest.java can drive it against a fake clock under a plain JVM. The
 * countdown is the one piece of this app with a contract a user would notice if it broke,
 * and it is worth being able to prove.
 *
 * <p><b>The freeze contract.</b> When the last task is completed the remaining time is
 * captured and returned from then on. A child who finishes with 3:42 left sees 3:42 for
 * the whole celebration; it does not keep ticking down behind the dance.
 *
 * <p>Progress along the adventure is driven by elapsed time, not by tasks completed. That
 * is existing behaviour, preserved deliberately: the collectibles measure the morning,
 * the task counter measures the routine, and they are two different things.
 */
final class Engine {

    /** Injectable so tests can run without {@code SystemClock}, which is native. */
    interface Clock {
        long nowMs();
    }

    /** Callbacks fire from state changes, never from drawing. */
    interface Listener {
        /** A task was ticked off. {@code last} is true when it was the final one. */
        void onTaskCompleted(int index, boolean last);
        /** Every task is done. {@code remainingMs} is the frozen time. */
        void onMissionComplete(long remainingMs);
        /** The countdown reached zero with tasks still outstanding. */
        void onTimeUp();
    }

    /** The adventure shows at most this many collectibles, however long the routine. */
    static final int MIN_COLLECTIBLES = 3;
    static final int MAX_COLLECTIBLES = 24;

    /**
     * The beat of one collectible action, in seconds: wind-up, three bites, recovery.
     * Long enough that each bite is legible as a separate go at the thing.
     */
    static final float FEAST_SECONDS = 2.0f;
    /** How much of that beat happens before the buddy reaches the item. */
    static final float FEAST_LEAD = 0.45f;

    /**
     * The stretch of the action beat spent eating, as a fraction of it.
     *
     * <p>The item is counted collected the instant the buddy reaches it, which is
     * {@link #FEAST_LEAD} / {@link #FEAST_SECONDS} into the beat. Eating opens a little
     * before that so the first chomp's wind-up happens on the approach, and closes before
     * the beat ends so the buddy has a moment to walk on with nothing in its mouth.
     *
     * <p>Here rather than in the screen that draws it because {@link #pollBite} has to do
     * the same arithmetic, and two copies of it would drift: the sound would land on a
     * different frame from the bite it belongs to.
     */
    static final float EAT_START = 0.12f, EAT_END = 0.82f;

    private static final String[] NO_TASKS = new String[0];

    private final Clock clock;
    private Listener listener;

    private String[] names = NO_TASKS;
    private String[] keys = NO_TASKS;
    private boolean[] done = new boolean[0];
    private int active;

    private boolean running;
    private long durationMs = 15L * 60_000L;
    private long endAt;
    /** Remaining time at the moment the mission was finished, or -1 while it runs. */
    private long completionRemainingMs = -1L;
    /** Remaining time while paused, or -1 when not paused. */
    private long pausedRemainingMs = -1L;
    private boolean timeUpFired;

    /** How many seconds of the countdown are ticked out loud at the end. */
    static final int TICK_SECONDS = 10;

    /**
     * How long before the end the buddy reaches the chest, in milliseconds.
     *
     * <p>Just wider than {@link #TICK_SECONDS}, so the chest is already open when the
     * last ten seconds start ticking rather than opening over the top of them.
     */
    static final long PRIZE_LEAD_MS = 12_000L;

    private boolean milestoneFired;
    private boolean prizeFired;
    /** The last whole second {@link #pollTick} reported, so each is reported once. */
    private int lastTickSecond = -1;

    /** Which collectible {@link #pollBite} is counting bites out of, or -1. */
    private int biteItem = -1;
    /** How many of that item's bites have already been reported. */
    private int bitesFired;

    Engine(Clock clock) {
        this.clock = clock;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------- routine

    /**
     * Replaces the routine and clears progress. Mismatched arrays are reconciled rather
     * than thrown, because both come from a preference string that a previous version
     * may have written.
     */
    void setRoutine(String[] taskNames, String[] taskKeys) {
        names = taskNames == null ? NO_TASKS : taskNames;
        keys = taskKeys == null ? NO_TASKS : taskKeys;
        if (keys.length != names.length) {
            String[] fixed = new String[names.length];
            for (int i = 0; i < names.length; i++) {
                fixed[i] = i < keys.length ? keys[i] : "DRESS";
            }
            keys = fixed;
        }
        done = new boolean[names.length];
        reset();
    }

    int taskCount() { return names.length; }

    String taskName(int i) { return i >= 0 && i < names.length ? names[i] : ""; }

    String taskKey(int i) { return i >= 0 && i < keys.length ? keys[i] : "DRESS"; }

    boolean isTaskDone(int i) { return i >= 0 && i < done.length && done[i]; }

    /** Index of the task being worked on; equals {@link #taskCount()} when finished. */
    int activeIndex() { return active; }

    String activeName() { return taskName(Math.min(active, names.length - 1)); }

    String activeKey() { return taskKey(Math.min(active, keys.length - 1)); }

    // ------------------------------------------------------------------- lifecycle

    /** Starts the countdown. Does nothing if it is already running. */
    void start(int seconds) {
        if (running) return;
        durationMs = Math.max(1, seconds) * 1000L;
        endAt = clock.nowMs() + durationMs;
        running = true;
        timeUpFired = false;
        completionRemainingMs = -1L;
        pausedRemainingMs = -1L;
        biteItem = -1;
        bitesFired = 0;
        milestoneFired = false;
        prizeFired = false;
        lastTickSecond = -1;
    }

    /** Clears progress and stops the countdown. */
    void reset() {
        for (int i = 0; i < done.length; i++) done[i] = false;
        active = 0;
        running = false;
        endAt = 0L;
        completionRemainingMs = -1L;
        pausedRemainingMs = -1L;
        timeUpFired = false;
        biteItem = -1;
        bitesFired = 0;
        milestoneFired = false;
        prizeFired = false;
        lastTickSecond = -1;
    }

    /** Sets the duration used by the next {@link #start}. */
    void setDurationSeconds(int seconds) {
        if (!running) durationMs = Math.max(1, seconds) * 1000L;
    }

    boolean isRunning() { return running; }

    boolean isPaused() { return pausedRemainingMs >= 0L; }

    void pause() {
        if (!running || isPaused() || allDone()) return;
        pausedRemainingMs = Math.max(0L, endAt - clock.nowMs());
    }

    void resume() {
        if (!isPaused()) return;
        endAt = clock.nowMs() + pausedRemainingMs;
        pausedRemainingMs = -1L;
    }

    // ------------------------------------------------------------------ completion

    /**
     * Ticks off the active task and advances past anything already done.
     *
     * @return true if a task was actually completed
     */
    boolean completeActive() {
        if (active < 0 || active >= done.length || done[active]) return false;
        int completed = active;
        done[completed] = true;
        while (active < done.length && done[active]) active++;

        boolean finished = allDone();
        if (finished) {
            // Freeze here, before anything else can observe the clock again.
            completionRemainingMs = remainingRaw();
            pausedRemainingMs = -1L;
        }
        if (listener != null) {
            listener.onTaskCompleted(completed, finished);
            if (finished) listener.onMissionComplete(completionRemainingMs);
        }
        return true;
    }

    /**
     * True when every task is done. An empty routine is never complete, so a child who
     * has deleted every task does not land straight on the celebration.
     */
    boolean allDone() {
        if (done.length == 0) return false;
        for (boolean b : done) if (!b) return false;
        return true;
    }

    int completedCount() {
        int n = 0;
        for (boolean b : done) if (b) n++;
        return n;
    }

    // ------------------------------------------------------------------------ time

    private long remainingRaw() {
        if (isPaused()) return pausedRemainingMs;
        return running ? Math.max(0L, endAt - clock.nowMs()) : durationMs;
    }

    /** Time left, frozen once the mission is complete. */
    long remainingMs() {
        if (allDone() && completionRemainingMs >= 0L) return completionRemainingMs;
        return remainingRaw();
    }

    long durationMs() { return durationMs; }

    int durationSeconds() { return (int) (durationMs / 1000L); }

    /** Remaining time at the moment of completion, or -1 if not finished. */
    long completionRemainingMs() { return completionRemainingMs; }

    /**
     * Polls for the countdown hitting zero. Called from the frame loop; fires
     * {@link Listener#onTimeUp} once. Returns true on the frame it fires.
     */
    boolean pollTimeUp() {
        if (timeUpFired || !running || allDone() || remainingMs() > 0L) return false;
        timeUpFired = true;
        if (listener != null) listener.onTimeUp();
        return true;
    }

    boolean isTimeUp() { return running && !allDone() && remainingMs() <= 0L; }

    /** How far through the morning, 0..1 by elapsed time. 1 once complete. */
    float progress() {
        if (allDone()) return 1f;
        if (durationMs <= 0L) return 0f;
        float p = (durationMs - remainingMs()) / (float) durationMs;
        return p < 0f ? 0f : (p > 1f ? 1f : p);
    }

    /** True in the last fifth of the morning, which drives the hurry animation. */
    boolean isLowTime() {
        return running && !allDone() && remainingMs() < durationMs * 0.2f;
    }

    // ----------------------------------------------------------------- collectibles

    /** How many treats the morning holds. All of them are shown, so this can be a lot. */
    int collectibleCount() {
        // Roughly one every three minutes. They are laid out as a board of every treat
        // rather than a couple visible at a time, so a long morning can carry plenty of
        // them; the cap is what keeps the board's tiles a size a child can make out.
        float minutes = durationSeconds() / 60f;
        int n = Math.round(minutes / 3f) + 3;
        return Math.max(MIN_COLLECTIBLES, Math.min(n, MAX_COLLECTIBLES));
    }

    /** Milliseconds into the countdown, clamped to the run. */
    private long elapsedMs() {
        long elapsed = durationMs - remainingMs();
        return elapsed < 0L ? 0L : (elapsed > durationMs ? durationMs : elapsed);
    }

    /** When collectible {@code index} (1-based) is reached, in milliseconds. */
    private long collectibleAt(int index) {
        return durationMs * index / collectibleCount();
    }

    /**
     * Seconds since the most recent collectible was reached, or {@link Float#MAX_VALUE}
     * before the first.
     *
     * <p>Derived from the clock rather than stored, so it survives a rotation and cannot
     * drift out of step with {@link #collectedCount()}. It is seconds and not a fraction
     * of a segment because the buddy's action has to run at the same speed whether the
     * morning is five minutes long or ninety.
     */
    float secondsSinceCollected() {
        int got = collectedCount();
        if (got <= 0 || durationMs <= 0L) return Float.MAX_VALUE;
        return Math.max(0f, (elapsedMs() - collectibleAt(got)) / 1000f);
    }

    /** Seconds until the next collectible is reached, or {@link Float#MAX_VALUE} if none. */
    float secondsUntilCollect() {
        int got = collectedCount();
        if (got >= collectibleCount() || durationMs <= 0L) return Float.MAX_VALUE;
        return Math.max(0f, (collectibleAt(got + 1) - elapsedMs()) / 1000f);
    }

    /**
     * The collectible action beat, 0..1, or -1 when the buddy is just walking.
     *
     * <p>Runs from {@link #FEAST_LEAD} seconds before reaching an item to the end of
     * {@link #FEAST_SECONDS}, so the wind-up happens on approach and the contact lands
     * on the item itself.
     */
    float feastBeat() {
        if (allDone()) return -1f;
        float until = secondsUntilCollect();
        if (until <= FEAST_LEAD) return (FEAST_LEAD - until) / FEAST_SECONDS;
        float since = secondsSinceCollected();
        float p = (since + FEAST_LEAD) / FEAST_SECONDS;
        return p < 1f ? p : -1f;
    }

    /**
     * How far through the eating window the beat is, 0 at the first bite's contact and
     * {@link Art#BITE_COUNT} when the item is gone.
     */
    static float eatPhase(float beat) {
        return (beat - EAT_START) / (EAT_END - EAT_START) * Art.BITE_COUNT;
    }

    /** How many bites are out of the item at this point in the beat, 0..BITE_COUNT. */
    static int bitesTaken(float beat) {
        if (beat < 0f) return Art.BITE_COUNT;          // the beat is over; it is gone
        float eaten = eatPhase(beat);
        if (eaten <= 0f) return 0;
        int taken = (int) eaten;
        return taken > Art.BITE_COUNT ? Art.BITE_COUNT : taken;
    }

    /**
     * True through the buddy's final approach to the treasure chest.
     *
     * <p>The last collectible is not a treat. It is the chest at the end of the lane, the
     * same one for every character, and it is not eaten -- the buddy arrives, the chest
     * opens, and it dances. So this gates three things: the chomp animation, the crunch
     * sounds, and which state {@link Anim#stateFor} picks.
     *
     * <p>The window is a span of clock, not the last collectible's own beat. Tying it to
     * the beat looked right and was not: a beat is {@link #FEAST_SECONDS} wide, so the
     * whole finale lasted under half a second and the chest, which takes 1.4s to swing
     * open, never got past its second frame. Nor can it be the last collectible's
     * segment, which on a long morning is minutes wide -- {@link #collectibleCount} caps
     * the count, so segments stretch rather than multiply.
     *
     * <p>{@link #PRIZE_LEAD_MS} on any morning long enough to afford it, and an eighth of
     * a short one, which leaves the hurry jiggle its own stretch beforehand on a
     * one-minute run instead of the dance swallowing it whole.
     */
    boolean atPrize() {
        if (allDone()) return true;
        int total = collectibleCount();
        if (collectedCount() >= total) return true;
        // Whichever comes first: the approach window, or a beat that has already reached
        // the chest. The second cannot currently precede the first, and is here because
        // "nothing bites the chest" is the invariant and should not rest on that holding.
        int item = beatItem();
        if (item > 0 && item >= total) return true;
        return running && remainingMs() <= prizeLeadMs();
    }

    /** How long the final approach lasts, in milliseconds. */
    long prizeLeadMs() {
        long eighth = durationMs / 8L;
        return eighth < PRIZE_LEAD_MS ? eighth : PRIZE_LEAD_MS;
    }

    /** Which collectible (1-based) the current beat is being spent on, or -1. */
    private int beatItem() {
        if (feastBeat() < 0f) return -1;
        // During the lead-in the buddy has not reached the item yet, so the count is
        // still one behind; for the rest of the beat it is the one just counted.
        return secondsUntilCollect() <= FEAST_LEAD ? collectedCount() + 1 : collectedCount();
    }

    /**
     * Which bite just landed, 0..{@code BITE_COUNT - 1}, or -1 for no bite this frame.
     *
     * <p>The same shape as {@link #pollTimeUp}, and for the same reason. Eating is a pure
     * function of the clock -- {@link #feastBeat} is read inside a draw call and holds no
     * state -- so there is no event to hang a sound on, and firing one from the draw is
     * the defect MorningView carries an explicit warning about. Edge-detecting it here
     * puts the event where the state is, and where tools/SelfTest.java can prove it fires
     * exactly three times per treat and never twice for the same bite.
     *
     * <p>Reports at most one bite per call. Bites are around half a second apart, so a
     * frame that spans two is a stall, and the next frame reporting the second one late
     * beats dropping it.
     */
    int pollBite() {
        float beat = feastBeat();
        if (beat < 0f || !running || isPaused()) {
            biteItem = -1;
            bitesFired = 0;
            return -1;
        }
        // The chest is not food. No bites out of it, and no crunches.
        //
        // A backstop, not the mechanism: nothing can reach here at the prize today. The
        // last collectible lands on the final tick, so only its lead-in ever elapses and
        // a lead-in has taken no bites; and the other route in, allDone(), makes
        // feastBeat() negative on the line above. What actually holds the invariant is
        // that timing, which tools/SelfTest.java measures by polling whole mornings.
        // This is here so a change to collectible spacing cannot quietly turn the chest
        // back into food, and it is cheaper than the bug would be.
        if (atPrize()) {
            biteItem = -1;
            bitesFired = 0;
            return -1;
        }
        int item = beatItem();
        if (item != biteItem) {
            biteItem = item;
            bitesFired = 0;
        }
        if (bitesTaken(beat) <= bitesFired) return -1;
        return bitesFired++;
    }

    /**
     * True on the single frame the morning passes its halfway mark.
     *
     * <p>Same shape as {@link #pollTimeUp} and {@link #pollBite}, and for the same
     * reason: the clock is read inside a draw, so the only honest place to notice it
     * crossing something is the frame loop.
     *
     * <p>Paused across the halfway point it fires on resume rather than not at all,
     * which is the right way round -- a cue nobody hears is a cue that was not written.
     */
    boolean pollMilestone() {
        if (milestoneFired || !running || allDone() || isPaused()) return false;
        if (progress() < 0.5f) return false;
        milestoneFired = true;
        return true;
    }

    /**
     * True on the one frame the buddy reaches the treasure chest.
     *
     * <p>The same shape as {@link #pollMilestone}, and here rather than in the adventure
     * screen for the same reason: the screen already latches the chest opening, but it
     * latches it inside its draw call, and a sound fired from a draw is the defect
     * MorningView carries an explicit warning about. The animation can live in the draw
     * because redrawing it twice costs nothing; playing the cue twice does not.
     */
    boolean pollPrize() {
        if (prizeFired || !running || isPaused() || !atPrize()) return false;
        prizeFired = true;
        return true;
    }

    /**
     * The whole second that just began, counting down through the last
     * {@link #TICK_SECONDS}, or -1.
     *
     * <p>Returns 10 down to 1 and each of them exactly once, so a caller can play one
     * tick per second without keeping its own clock -- and so a stalled frame reports
     * the second late rather than losing it.
     */
    int pollTick() {
        if (!running || allDone() || isPaused()) return -1;
        long left = remainingMs();
        if (left <= 0L || left > TICK_SECONDS * 1000L) return -1;
        int second = (int) ((left + 999L) / 1000L);
        if (second == lastTickSecond) return -1;
        lastTickSecond = second;
        return second;
    }

    /** How many have been picked up so far. */
    int collectedCount() {
        int total = collectibleCount();
        if (allDone()) return total;
        return Math.min(total, (int) Math.floor(progress() * total));
    }

    /**
     * How far into the current collectible, 0..1. Near zero means one was just reached,
     * which is the cue for the buddy's munch reaction.
     */
    float collectibleFraction() {
        float exact = progress() * collectibleCount();
        return exact - (float) Math.floor(exact);
    }
}
