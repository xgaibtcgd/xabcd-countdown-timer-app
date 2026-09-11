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
    static final int MAX_COLLECTIBLES = 18;

    /**
     * The beat of one collectible action, in seconds: wind-up, three bites, recovery.
     * Long enough that each bite is legible as a separate go at the thing.
     */
    static final float FEAST_SECONDS = 2.0f;
    /** How much of that beat happens before the buddy reaches the item. */
    static final float FEAST_LEAD = 0.45f;

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
    void start(int minutes) {
        if (running) return;
        durationMs = Math.max(1, minutes) * 60_000L;
        endAt = clock.nowMs() + durationMs;
        running = true;
        timeUpFired = false;
        completionRemainingMs = -1L;
        pausedRemainingMs = -1L;
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
    }

    /** Sets the duration used by the next {@link #start}. */
    void setDurationMinutes(int minutes) {
        if (!running) durationMs = Math.max(1, minutes) * 60_000L;
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

    int durationMinutes() { return (int) (durationMs / 60_000L); }

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

    /** How many collectibles the trail shows: one a minute, capped so they stay legible. */
    int collectibleCount() {
        int minutes = Math.max(1, durationMinutes());
        // Roughly one every two minutes, so a longer morning really does have more to
        // find. Floored at three so even a one-minute timer is a journey rather than a
        // single stop, and capped so the gaps never close up into a crowd.
        int n = Math.round(minutes / 2f) + 2;
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
