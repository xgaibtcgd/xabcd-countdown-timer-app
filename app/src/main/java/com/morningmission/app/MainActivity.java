package com.morningmission.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
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
    private SoundPool soundPool;
    private final int[] soundIds = new int[BuddyTheme.COUNT];

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
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (morningView != null) morningView.startClock();
    }

    @Override protected void onStop() {
        releaseVictory();
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
        if (!prefs.contains("minutes")) prefs.edit().putInt("minutes", 15).apply();
        // The shark is the buddy every mockup leads with, so it is what a new install
        // opens on. Previously it opened on the burger.
        if (!prefs.contains("buddy")) prefs.edit().putInt("buddy", 3).apply();
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
                .setMaxStreams(4)
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
        }
    }

    void playBuddySound(int buddyIndex) {
        if (!prefs.getBoolean("song", true)) return;
        int i = BuddyTheme.clampIndex(buddyIndex);
        if (soundPool == null || soundIds[i] == 0) return;
        try {
            soundPool.play(soundIds[i], 0.72f, 0.72f, 1, 0, 1f);
        } catch (Exception ignored) {
            // A sound failing is never worth interrupting a child's morning.
        }
    }

    void playVictory() {
        if (!prefs.getBoolean("song", true)) return;
        try {
            releaseVictory();
            victoryPlayer = MediaPlayer.create(this, R.raw.victory);
            if (victoryPlayer != null) victoryPlayer.start();
        } catch (Exception ignored) {
        }
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
            stopKidMode();
            morningView.resetRoutine();
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

    static Context context(View view) {
        return view.getContext();
    }
}
