package com.bellamiecreative.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

/**
 * Plays the user-supplied iPhone keyboard recordings (res/raw/snd_*.wav) with SoundPool.
 *
 * - Samples are decoded once, in memory; nothing touches the disk while typing.
 * - play() is thread-safe and is called straight from the WebView bridge thread (no main-thread hop).
 * - The existing sound ON/OFF preference and the 0-100 volume preference are cached and
 *   kept current by a change listener, so no preference read happens per key press.
 */
final class KeySoundEngine {
    // Sound slots. Slot -> recording: see SOUND_ASSETS_NOTICE.md.
    static final int LETTER = 0;      // 01  letters, numbers, symbols, emoji
    static final int SHIFT_ON = 1;    // 03  Shift on; also the fallback for unlisted controls
    static final int SHIFT_OFF = 2;   // 04  Shift off
    static final int CAPS_LOCK = 3;   // 02  second click only (second Shift tap)
    static final int KEY_123 = 4;     // 05  123 / ABC layout key
    static final int EMOJI_KEY = 5;   // 06  emoji key
    static final int SPACE = 6;       // 07  space bar
    static final int RETURN = 7;      // 08  return / enter
    static final int BS_TAP = 8;      // 09  backspace press
    static final int BS_HOLD = 9;     // 10  one repeat tick of the held backspace
    private static final int COUNT = 10;
    static final int FALLBACK = SHIFT_ON;

    private static final String PREFS = "keyboard_settings";
    private static final String KEY_ENABLED = "sound_enabled";
    private static final String KEY_VOLUME = "sound_volume";
    private static final long DUPLICATE_GUARD_MS = 8;   // same slot twice within 8 ms = duplicate event, ignore

    private static final int[] RES = {
            R.raw.snd_letter, R.raw.snd_shift_on, R.raw.snd_shift_off, R.raw.snd_caps_lock,
            R.raw.snd_key_123, R.raw.snd_emoji_key, R.raw.snd_space, R.raw.snd_return,
            R.raw.snd_backspace_tap, R.raw.snd_backspace_hold};

    private final Context context;
    private final SharedPreferences prefs;
    private final BackspaceHoldPacer holdPacer = new BackspaceHoldPacer();
    private final int[] soundIds = new int[COUNT];
    private final boolean[] loaded = new boolean[COUNT];
    private final long[] lastPlayed = new long[COUNT];
    private volatile boolean enabled = true;
    private volatile float volume = 1f;
    private volatile SoundPool pool;

    // Kept in a field: SharedPreferences holds listeners weakly.
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (p, key) -> {
                if (key == null || KEY_ENABLED.equals(key)) {
                    enabled = p.getBoolean(KEY_ENABLED, true);
                }
                if (key == null || KEY_VOLUME.equals(key)) {
                    int percent = Math.max(0, Math.min(100, p.getInt(KEY_VOLUME, 100)));
                    volume = percent / 100f;
                }
            };

    KeySoundEngine(Context ctx) {
        context = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        enabled = prefs.getBoolean(KEY_ENABLED, true);
        int savedVolume = Math.max(0, Math.min(100, prefs.getInt(KEY_VOLUME, 100)));
        volume = savedVolume / 100f;
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        Thread loader = new Thread(this::loadAll, "KeySoundLoader");
        loader.setPriority(Thread.NORM_PRIORITY);
        loader.start();
    }

    private void loadAll() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            SoundPool sp = new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build();
            sp.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
                if (status != 0) return;
                synchronized (this) {
                    for (int i = 0; i < COUNT; i++) if (soundIds[i] == sampleId) loaded[i] = true;
                }
            });
            synchronized (this) {
                for (int i = 0; i < COUNT; i++) soundIds[i] = sp.load(context, RES[i], 1);
            }
            pool = sp;
        } catch (RuntimeException ignored) {
            // No sound is better than a crashing keyboard.
        }
    }

    boolean isEnabled() { return enabled; }

    /**
     * Plays the sound for a page-supplied kind. Unknown kinds use the Shift-ON fallback; "none"/"silent" play nothing.
     * (Globe and Voice never call the bridge at all, so they can never reach this.)
     */
    void playKind(String kind) {
        if (kind == null) { play(FALLBACK); return; }
        switch (kind) {
            case "none": case "silent": return;
            case "letter": play(LETTER); return;
            case "shiftOn": play(SHIFT_ON); return;
            case "shiftOff": play(SHIFT_OFF); return;
            case "caps": play(CAPS_LOCK); return;
            case "n123": play(KEY_123); return;
            case "emoji": play(EMOJI_KEY); return;
            case "space": play(SPACE); return;
            case "return": play(RETURN); return;
            case "bsTap": holdPacer.reset(); play(BS_TAP); return;
            case "bsHold":
                if (enabled && volume > 0f && holdPacer.onRepeatTick(SystemClock.uptimeMillis())) play(BS_HOLD);
                return;
            default: play(FALLBACK);
        }
    }

    void play(int slot) {
        if (!enabled) return;                       // the existing switch controls every sound
        float currentVolume = volume;
        if (currentVolume <= 0f) return;             // 0% is completely silent
        SoundPool sp = pool;
        if (sp == null || slot < 0 || slot >= COUNT) return;
        int id;
        synchronized (this) {
            if (!loaded[slot]) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastPlayed[slot] < DUPLICATE_GUARD_MS) return;
            lastPlayed[slot] = now;
            id = soundIds[slot];
        }
        sp.play(id, currentVolume, currentVolume, 1, 0, 1f);
    }

    void release() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        SoundPool sp = pool;
        pool = null;
        if (sp != null) sp.release();
    }
}
