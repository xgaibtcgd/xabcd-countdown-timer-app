package com.morningmission.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The activity: lifecycle, preferences, sound, the kid lock, and the few dialogs that are
 * genuinely better as system dialogs.
 *
 * <p>All drawing and all geometry moved out to {@link MorningView} and the {@link Screen}
 * classes. What used to be one 407-line file containing an activity, an entire UI and the
 * PIN cryptography is now this plus a package of focused classes.
 *
 * <p>The kid lock and the PIN are unchanged from the shipped build, on purpose: a visual
 * release is not the place to alter the gate that keeps a child inside the app.
 */
public final class MainActivity extends Activity {

    /** Read directly by MorningView; this is the app's whole persistence layer. */
    SharedPreferences prefs;

    private MorningView morningView;
    private DevicePolicyManager devicePolicy;
    private ComponentName adminComponent;
    private boolean kidLocked;

    private MediaPlayer victoryPlayer;
    /** The title-screen loop. Held open while Home is showing, released otherwise. */
    private MediaPlayer titlePlayer;
    private boolean titleMusicWanted;
    private SoundPool soundPool;
    private final int[] soundIds = new int[BuddyTheme.COUNT];
    private final int[] eatIds = new int[BuddyTheme.COUNT];
    private final int[] uiIds = new int[Sounds.UI_COUNT];
    private final int[] cueIds = new int[Sounds.CUE_COUNT];
    private final int[] pokeIds = new int[Sounds.POKE_COUNT];
    private final int[] actIds = new int[Sounds.ACTIVITY.length];

    private static final String[] DEFAULT_TASK_NAMES =
            {"Get Dressed", "Breakfast", "Brush Teeth", "Shoes On", "Backpack"};
    private static final String[] DEFAULT_TASK_KEYS =
            {"DRESS", "EAT", "BRUSH", "SHOES", "PACK"};

    // ------------------------------------------------------------------- lifecycle

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("morning_mission", MODE_PRIVATE);
        ensureDefaults();

        devicePolicy = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MorningDeviceAdminReceiver.class);

        morningView = new MorningView(this, this);
        setContentView(morningView);
        morningView.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                morningView.setSystemInsets(bars.top, bars.bottom);
            } else {
                morningView.setSystemInsets(insets.getSystemWindowInsetTop(),
                                            insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        prepareSounds();
        if (!PinSecurity.hasPin(prefs)) {
            morningView.postDelayed(this::showCreatePin, 350L);
        }
    }

    @Override protected void onPause() {
        // The old build's frame loop ran forever, including after the activity was gone.
        if (morningView != null) morningView.stopClock();
        // Music must not keep playing out of a backgrounded kids' app.
        releaseTitleMusic();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (morningView != null) morningView.startClock();
        if (titleMusicWanted) startTitleMusic();
    }

    @Override protected void onStop() {
        releaseVictory();
        releaseTitleMusic();
        super.onStop();
    }

    @Override protected void onDestroy() {
        releaseVictory();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (kidLocked) {
            requestParentUnlock();
            return;
        }
        if (morningView != null && morningView.onBackPressed()) return;
        super.onBackPressed();
    }

    private void ensureDefaults() {
        // Seeded only when neither key is present: an install that already has the old
        // whole-minute key keeps it, and MorningView.durationSeconds scales it forward.
        if (!prefs.contains(MorningView.DURATION_KEY) && !prefs.contains("minutes")) {
            prefs.edit().putInt(MorningView.DURATION_KEY, 15 * 60).apply();
        }
        // The shark is the buddy every mockup leads with, so it is what a new install
        // opens on. Previously it opened on the burger.
        if (!prefs.contains("buddy")) prefs.edit().putInt("buddy", 3).apply();
        if (!prefs.contains("music")) prefs.edit().putBoolean("music", true).apply();
        if (!prefs.contains("task_names")) saveRoutine(DEFAULT_TASK_NAMES, DEFAULT_TASK_KEYS);
    }

    // ------------------------------------------------------------------ the routine

    String[] loadTaskNames() {
        return prefs.getString("task_names", String.join("|", DEFAULT_TASK_NAMES))
                    .split("\\|", -1);
    }

    String[] loadTaskKeys() {
        return prefs.getString("task_keys", String.join("|", DEFAULT_TASK_KEYS))
                    .split("\\|", -1);
    }

    void saveRoutine(String[] names, String[] keys) {
        prefs.edit()
             .putString("task_names", String.join("|", names))
             .putString("task_keys", String.join("|", keys))
             .apply();
    }

    // ----------------------------------------------------------------------- sound

    /**
     * Short sounds play through a SoundPool. {@code MediaPlayer.create} costs around
     * eighty milliseconds every time it is called, which was audible as a lag between
     * ticking off a task and the buddy responding.
     */
    private void prepareSounds() {
        soundPool = new SoundPool.Builder()
                // Three bites inside 1.4 seconds, a poke on top of any of them, an
                // interface tap and a task cue. Eight is the first number this stops
                // being able to reach.
                .setMaxStreams(8)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            try {
                soundIds[i] = soundPool.load(this, BuddyTheme.ALL[i].soundRes, 1);
            } catch (Exception ignored) {
                soundIds[i] = 0;
            }
            try {
                eatIds[i] = soundPool.load(this, BuddyTheme.ALL[i].eatRes, 1);
            } catch (Exception ignored) {
                eatIds[i] = 0;
            }
        }
        load(Sounds.UI, uiIds);
        load(Sounds.CUE, cueIds);
        load(Sounds.POKE, pokeIds);
        load(Sounds.ACTIVITY, actIds);
    }

    /** Loads a table of raw resources into a parallel table of pool ids. */
    private void load(int[] resources, int[] into) {
        for (int i = 0; i < resources.length && i < into.length; i++) {
            try {
                into[i] = soundPool.load(this, resources[i], 1);
            } catch (Exception ignored) {
                into[i] = 0;
            }
        }
    }

    /** The one place a pooled sound is actually played. Everything below goes through it. */
    private void play(int[] ids, int index, float volume, float rate) {
        if (!prefs.getBoolean("song", true)) return;
        if (soundPool == null || index < 0 || index >= ids.length || ids[index] == 0) return;
        try {
            soundPool.play(ids[index], volume, volume, 1, 0, rate);
        } catch (Exception ignored) {
            // A sound failing is never worth interrupting a child's morning.
        }
    }

    /**
     * An interface sound. Quiet on purpose.
     *
     * <p>This is the one set that fires on every touch anywhere in the app, so it is the
     * one that can turn into a headache in a kitchen at seven in the morning. It sits
     * well below the characters, which are the sounds meant to be noticed.
     *
     * @param which a {@code Sounds.UI_*} constant
     */
    void playUi(int which) {
        play(uiIds, which, 0.34f, 1f);
    }

    /** The cue for the task a child should be doing now. @param kind an Art.ACT_* value */
    void playActivity(int kind) {
        play(actIds, kind, 0.58f, 1f);
    }

    /** The goal opening, the halfway chime, the last-ten-seconds tick. */
    void playCue(int which) {
        play(cueIds, which, which == Sounds.CUE_TICK ? 0.30f : 0.62f, 1f);
    }

    /**
     * A reaction to being poked, pitched to the character doing it.
     *
     * <p>Three shared files rather than twenty-four: the rate argument carries the
     * difference between a bee giggling and a triceratops giggling, and a per-character
     * giggle is not a distinction anybody would notice.
     */
    void playPoke(int which, int buddyIndex) {
        float rate = 0.86f + 0.05f * BuddyTheme.clampIndex(buddyIndex);
        play(pokeIds, which, 0.66f, rate);
    }

    void playBuddySound(int buddyIndex) {
        play(soundIds, BuddyTheme.clampIndex(buddyIndex), 0.72f, 1f);
    }

    /**
     * One bite of a treat, rising in pitch across the three.
     *
     * <p>The rate argument on {@code SoundPool.play} was hardcoded to 1 at the only call
     * site and unused everywhere, so it was free: three plays of one sample at the same
     * pitch read as the treat being hit three times, and the same three rising read as it
     * being finished.
     *
     * @param bite 0, 1 or 2 -- which bite of the treat this is
     */
    void playEatSound(int buddyIndex, int bite) {
        float rate = 1f + 0.08f * Math.max(0, Math.min(bite, Art.BITE_COUNT - 1));
        play(eatIds, BuddyTheme.clampIndex(buddyIndex), 0.62f, rate);
    }

    /**
     * The Mission Complete fanfare, which is this character's and nobody else's.
     *
     * <p>Eight of them, in eight styles. Kept on MediaPlayer rather than the SoundPool
     * everything else uses because these are five-second 44.1 kHz pieces, not blips --
     * the ~80 ms MediaPlayer.create costs lands inside the 420 ms the caller already
     * waits before starting it.
     */
    void playVictory(int buddyIndex) {
        if (!prefs.getBoolean("song", true)) return;
        try {
            releaseVictory();
            victoryPlayer = MediaPlayer.create(this, BuddyTheme.of(buddyIndex).victoryRes);
            if (victoryPlayer == null) return;
            // Under full scale: it plays over confetti, a page turn and whatever the
            // child taps next, and at 1.0 it was the loudest thing in the app by far.
            victoryPlayer.setVolume(0.85f, 0.85f);
            // Release itself when it ends. Without this a finished player was held
            // until the next fanfare or onStop, which is a leak the title music does
            // not have because that one loops and is released deliberately.
            victoryPlayer.setOnCompletionListener(player -> releaseVictory());
            victoryPlayer.start();
        } catch (Exception ignored) {
        }
    }

    /**
     * Stops anything still sounding. Called when the sounds preference is turned off.
     *
     * <p>The fanfare is five seconds long and the mute chip exists to stop noise NOW.
     * Without this, muting mid-celebration left the jingle playing over the top of the
     * gesture that was meant to silence it.
     */
    void silence() {
        releaseVictory();
    }

    /**
     * Turns the title loop on or off as screens change.
     *
     * <p>Remembers what was asked for even when it cannot act on it, so coming back
     * from the background restores whatever the current screen wanted rather than
     * leaving the title screen silent.
     */
    void setTitleMusic(boolean wanted) {
        titleMusicWanted = wanted;
        if (wanted) startTitleMusic(); else releaseTitleMusic();
    }

    private void startTitleMusic() {
        if (!prefs.getBoolean("music", true)) return;
        if (titlePlayer != null) return;
        try {
            titlePlayer = MediaPlayer.create(this, R.raw.title_song);
            if (titlePlayer == null) return;
            titlePlayer.setLooping(true);
            // Well under the tap sounds: this runs for as long as the screen is open.
            titlePlayer.setVolume(0.34f, 0.34f);
            titlePlayer.start();
        } catch (Exception ignored) {
            titlePlayer = null;
        }
    }

    private void releaseTitleMusic() {
        if (titlePlayer == null) return;
        try {
            if (titlePlayer.isPlaying()) titlePlayer.stop();
            titlePlayer.release();
        } catch (Exception ignored) {
        }
        titlePlayer = null;
    }

    /** Re-reads the music preference, for the Grown-Ups toggle. */
    void refreshTitleMusic() {
        if (titleMusicWanted && prefs.getBoolean("music", true)) startTitleMusic();
        else releaseTitleMusic();
    }

    private void releaseVictory() {
        if (victoryPlayer == null) return;
        try {
            if (victoryPlayer.isPlaying()) victoryPlayer.stop();
            victoryPlayer.release();
        } catch (Exception ignored) {
        }
        victoryPlayer = null;
    }

    // -------------------------------------------------------------------- kid lock

    boolean isKidLocked() { return kidLocked; }

    void startKidMode() {
        if (!PinSecurity.hasPin(prefs)) {
            showCreatePin();
            return;
        }
        try {
            if (devicePolicy.isDeviceOwnerApp(getPackageName())) {
                devicePolicy.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
                if (Build.VERSION.SDK_INT >= 28) {
                    devicePolicy.setLockTaskFeatures(adminComponent,
                            DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
                }
            }
            startLockTask();
            kidLocked = true;
            setKidImmersive(true);
            morningView.invalidate();
        } catch (Exception e) {
            toast("Routine started. Full lock is not provisioned yet.");
        }
    }

    private void stopKidMode() {
        try {
            stopLockTask();
        } catch (Exception ignored) {
        }
        kidLocked = false;
        setKidImmersive(false);
        morningView.invalidate();
    }

    void toggleKidLock() {
        if (kidLocked) requestParentUnlock();
        else startKidMode();
    }

    private void setKidImmersive(boolean enabled) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) return;
            int bars = WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars();
            if (enabled) controller.hide(bars);
            else controller.show(bars);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(enabled
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                      | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    : View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    // -------------------------------------------------------------------- the PIN

    void requestParentUnlock() {
        askForPin("Parent Unlock", "Enter the Grown-Ups PIN to leave Morning Mission.", () -> {
            // The countdown keeps running and completed tasks stay completed. A grown-up
            // stepping in to check something should not cost a child their morning's
            // progress; Home offers a way straight back in.
            stopKidMode();
            morningView.route(MorningView.SCREEN_HOME);
        });
    }

    void openGrownUps() {
        if (!PinSecurity.hasPin(prefs)) {
            showCreatePin();
            return;
        }
        askForPin("Grown-Ups", "Enter your PIN to open settings.",
                  () -> morningView.route(MorningView.SCREEN_GROWN_UPS));
    }

    void openRoutineEditor() {
        if (!PinSecurity.hasPin(prefs)) {
            showCreatePin();
            return;
        }
        askForPin("Edit Routine", "Enter your PIN to change the morning routine.",
                  () -> morningView.route(MorningView.SCREEN_EDIT_ROUTINE));
    }

    private void showCreatePin() {
        LinearLayout box = dialogBox();
        box.addView(label("Create a 4 to 6 digit Grown-Ups PIN. It protects settings and "
                          + "Parent Unlock.", 16));
        EditText first = pinField("New PIN");
        EditText second = pinField("Confirm PIN");
        box.addView(first);
        box.addView(second);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Set Grown-Ups PIN")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton(PinSecurity.hasPin(prefs) ? "Cancel" : "", null)
                .create();
        dialog.setCancelable(PinSecurity.hasPin(prefs));
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String a = first.getText().toString();
                    String b = second.getText().toString();
                    if (!a.matches("\\d{4,6}")) {
                        first.setError("Use 4 to 6 numbers");
                        return;
                    }
                    if (!a.equals(b)) {
                        second.setError("PINs do not match");
                        return;
                    }
                    PinSecurity.savePin(prefs, a);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void askForPin(String title, String message, Runnable onSuccess) {
        LinearLayout box = dialogBox();
        box.addView(label(message, 16));
        EditText pin = pinField("PIN");
        box.addView(pin);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Continue", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (PinSecurity.verify(prefs, pin.getText().toString())) {
                        dialog.dismiss();
                        onSuccess.run();
                    } else {
                        pin.setError("Incorrect PIN");
                        pin.setText("");
                    }
                }));
        dialog.show();
    }

    // --------------------------------------------------------------- task dialogs

    void editTask(ScreenEditRoutine screen, int index) {
        LinearLayout box = dialogBox();
        EditText name = new EditText(this);
        name.setText(screen.nameAt(index));
        box.addView(name);

        Spinner activity = new Spinner(this);
        activity.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, Art.ACTIVITY_NAMES));
        activity.setSelection(Art.activityKind(screen.keyAt(index)));
        box.addView(activity);
        box.addView(label("The activity sets the icon on the card and how your buddy acts "
                          + "while the task is on.", 14));

        new AlertDialog.Builder(this)
                .setTitle("Edit Task")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    int kind = activity.getSelectedItemPosition();
                    String typed = name.getText().toString().trim();
                    screen.applyTask(index,
                                     typed.isEmpty() ? Art.ACTIVITY_NAMES[kind] : typed,
                                     Art.ACTIVITY_KEYS[kind]);
                })
                .setNeutralButton("Remove", (d, w) -> screen.removeTask(index))
                .setNegativeButton("Cancel", null)
                .show();
    }

    void addTask(ScreenEditRoutine screen) {
        new AlertDialog.Builder(this)
                .setTitle("Choose a Task")
                .setItems(Art.ACTIVITY_NAMES, (d, which) ->
                        screen.addTask(Art.ACTIVITY_NAMES[which], Art.ACTIVITY_KEYS[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    void showAbout() {
        boolean owner = devicePolicy != null && devicePolicy.isDeviceOwnerApp(getPackageName());
        String body = "Morning Mission helps children get ready in time, with a buddy to "
                + "keep them company.\n\n"
                + "Everything runs on this device. There is no account, no internet "
                + "connection and no data leaves the tablet or phone.\n\n"
                + (owner
                   ? "Full Kid Lock is available on this device."
                   : "Standard screen pinning is in use. Full Kid Lock needs the app to be "
                     + "set as device owner.");
        new AlertDialog.Builder(this)
                .setTitle("About Morning Mission")
                .setMessage(body)
                .setPositiveButton("Close", null)
                .show();
    }

    void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // --------------------------------------------------------------- dialog pieces

    private LinearLayout dialogBox() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, pad / 2, pad, pad / 2);
        return layout;
    }

    private TextView label(String text, int sizeSp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.rgb(38, 54, 94));
        view.setPadding(0, dp(6), 0, dp(10));
        return view;
    }

    private EditText pinField(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setMaxEms(6);
        return field;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
