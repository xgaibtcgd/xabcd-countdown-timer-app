package com.morningmission.app;

/**
 * Formats the countdown as m:ss without allocating.
 *
 * <p>The old build called {@code String.format} inside {@code onDraw}, allocating a
 * String and a Formatter on every frame. This writes into a reusable buffer that
 * {@link Theme#drawTime} hands to the {@code char[]} overload of {@code drawText}.
 *
 * <p>Kept in its own class, free of any graphics import, so tools/SelfTest.java can check
 * the formatting of the frozen completion time off-device. Anything that touches
 * {@link Theme} pulls in Typeface, which is native and unavailable there.
 *
 * <p>Main-thread only, like all drawing code: the buffer is shared.
 */
final class TimeText {

    private TimeText() {}

    /** Enough for "999:59". */
    static final char[] BUFFER = new char[8];

    /**
     * Writes {@code ms} into {@code out} as m:ss, rounding part-seconds up so the display
     * shows 1:00 rather than 0:59 for the last moment of a minute, and returns the number
     * of characters written. Negative input clamps to zero.
     */
    static int format(long ms, char[] out) {
        long totalSeconds = (Math.max(0L, ms) + 999L) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        int i = 0;
        if (minutes >= 100L) out[i++] = (char) ('0' + (minutes / 100L) % 10L);
        if (minutes >= 10L)  out[i++] = (char) ('0' + (minutes / 10L) % 10L);
        out[i++] = (char) ('0' + minutes % 10L);
        out[i++] = ':';
        out[i++] = (char) ('0' + seconds / 10L);
        out[i++] = (char) ('0' + seconds % 10L);
        return i;
    }

    /** Fills the shared buffer; returns the character count. */
    static int format(long ms) {
        return format(ms, BUFFER);
    }

    /**
     * A duration as a person would say it, for settings rows: "15 min" when it lands on
     * a whole number of minutes, "1:30" when it does not, "45 sec" under a minute.
     */
    static String describe(int seconds) {
        if (seconds < 60) return Math.max(0, seconds) + " sec";
        if (seconds % 60 == 0) return (seconds / 60) + " min";
        return toText(seconds * 1000L);
    }

    /** Allocating form, for the few places a String has to be composed. */
    static String toText(long ms) {
        char[] out = new char[8];
        return new String(out, 0, format(ms, out));
    }
}
