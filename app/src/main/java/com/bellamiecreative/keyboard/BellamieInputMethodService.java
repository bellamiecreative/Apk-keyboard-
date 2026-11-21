package com.bellamiecreative.keyboard;

import android.inputmethodservice.InputMethodService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.net.Uri;
import android.view.inputmethod.InputContentInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.os.UserManager;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.HapticFeedbackConstants;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class BellamieInputMethodService extends InputMethodService {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private KeyPopupOverlay popupOverlay; // first-row key popup drawn outside the WebView bounds
    private KeySoundEngine soundEngine;   // recorded iPhone key sounds (SoundPool); gated by the "sound_enabled" preference
    private EditorInfo editorInfo;
    private volatile int cachedCapsMode;
    private boolean pageReady;
    private boolean voiceReceiverRegistered;
    private boolean imageReceiverRegistered;
    // Password & Text Manager storage (created lazily on first use)
    private SecureCodec secureCodec;
    private RecordStore passwordStore;
    private RecordStore textStore;
    // ---- Space-bar trackpad (iPhone-style cursor control) ----------------------------------
    // Tuning: finger travel, in 390-wide CSS px (already normalised by keyboard.html), per cursor step.
    private static final float TRACK_H_PX = 8.5f;     // one character
    private static final float TRACK_V_PX = 20f;      // one visual line
    private static final int TRACK_MAX_H_STEPS = 12;  // per pass; any remainder is carried to the next pass
    private static final int TRACK_MAX_WINDOW = 160;  // chars read on each side of the caret for boundary checks
    private static final int TRACK_LINE_WINDOW = 1500; // chars read on each side by the logical-line fallback
    private static final long TRACK_CARRY_MS = 16;
    private static final long TRACK_PROBE_MS = 350;
    private final Object trackLock = new Object();
    private float pendingDx, pendingDy;     // written by the JS bridge thread, drained on the main thread (trackLock)
    private boolean drainScheduled;         // (trackLock)
    // Everything below is touched on the main thread only.
    private boolean trackActive, trackMultiLine, trackKeys, trackMonitoring;
    private float trackAccX, trackAccY;
    private int trackPos = -1;              // absolute caret index while the logical fallback is in use, else -1
    private int selStart = -1, selEnd = -1; // last selection reported by the host
    private CursorAnchorInfo lastAnchor;
    private long anchorAt, lastMoveAt;
    private int dpadState, probeMisses;     // dpadState: 0 unknown, 1 host reacts to key events, -1 ignores them
    private boolean probePending;
    private int probeStart, probeEnd;
    private long probeAt;
    private BreakIterator graphemes;
    private final Runnable drainRunnable = this::drainTrack;
    private final BroadcastReceiver imageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String uri = intent.getStringExtra("image_uri");
            if (uri != null && webView != null && pageReady) {
                String encoded = org.json.JSONObject.quote(uri);
                webView.evaluateJavascript("if(window.addCustomSticker)window.addCustomSticker(" + encoded + ");", null);
            }
        }
    };
    private final BroadcastReceiver voiceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String recognized = intent.getStringExtra("recognized_text");
            if (recognized == null || recognized.isEmpty()) return;
            InputConnection connection = getCurrentInputConnection();
            if (connection != null) connection.commitText(recognized, 1);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter("com.bellamiecreative.keyboard.VOICE_RESULT");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(voiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(voiceReceiver, filter);
        }
        voiceReceiverRegistered = true;
        IntentFilter imageFilter = new IntentFilter("com.bellamiecreative.keyboard.IMAGE_PICKED");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(imageReceiver, imageFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(imageReceiver, imageFilter);
        imageReceiverRegistered = true;
        soundEngine = new KeySoundEngine(this);
    }

    @Override public void onDestroy() {
        stopCursorTrack(false);
        releasePopupOverlay();
        if (soundEngine != null) { soundEngine.release(); soundEngine = null; }
        if (imageReceiverRegistered) { unregisterReceiver(imageReceiver); imageReceiverRegistered = false; }
        if (voiceReceiverRegistered) {
            unregisterReceiver(voiceReceiver);
            voiceReceiverRegistered = false;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override public View onCreateInputView() {
        pageReady = false;
        releasePopupOverlay();
        stopCursorTrack(false);
        webView = new WebView(this);
        popupOverlay = new KeyPopupOverlay(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDomStorageEnabled(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        webView.setBackgroundColor(0xFFD1D5DB);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                view.evaluateJavascript("if(window.resetForInput)window.resetForInput();", null);
                applyEditorConfiguration();
            }
        });
        webView.addJavascriptInterface(new KeyboardBridge(), "AndroidKeyboard");
        int keyboardHeight = keyboardHeightPx(0);
        webView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight));
        webView.loadUrl("file:///android_asset/keyboard.html");
        return webView;
    }

    @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        stopCursorTrack(false);
        editorInfo = attribute;
        selStart = attribute != null ? attribute.initialSelStart : -1;
        selEnd = attribute != null ? attribute.initialSelEnd : -1;
        dpadState = 0; probeMisses = 0; probePending = false;
        updateCachedCapsMode();
        applyEditorConfiguration();
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (webView != null && pageReady) {
            webView.evaluateJavascript("if(window.resetForInput)window.resetForInput();", null);
            applyEditorConfiguration();
        }
    }

    @Override public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart,
            int newSelEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        selStart = newSelStart;
        selEnd = newSelEnd;
        noteSelectionEcho(newSelStart, newSelEnd);
        // While Space is being dragged, skip the caps / empty-field work (an IPC round trip per update);
        // stopCursorTrack() refreshes it once when the finger lifts.
        if (trackActive) return;
        updateCachedCapsMode();
        if (webView != null && pageReady) {
            if (isCurrentInputEmpty()) {
                webView.evaluateJavascript("if(window.onInputBecameEmpty)window.onInputBecameEmpty();", null);
            } else {
                webView.evaluateJavascript("if(window.refreshCaps)window.refreshCaps();", null);
            }
        }
    }

    private boolean isCurrentInputEmpty() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return false;
        try {
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.token = 0;
            ExtractedText extracted = connection.getExtractedText(request, 0);
            return extracted != null && extracted.text != null && extracted.text.length() == 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        stopCursorTrack(false);
        hidePopupOverlay();
        super.onFinishInputView(finishingInput);
    }

    @Override public void onWindowHidden() {
        stopCursorTrack(false);
        hidePopupOverlay();
        super.onWindowHidden();
    }

    private void hidePopupOverlay() {
        KeyPopupOverlay overlay = popupOverlay;
        if (overlay != null) overlay.hide(-1);
    }

    private void releasePopupOverlay() {
        KeyPopupOverlay overlay = popupOverlay;
        popupOverlay = null;
        if (overlay != null) overlay.release();
    }

    @Override public void onFinishInput() {
        stopCursorTrack(false);
        super.onFinishInput();
        editorInfo = null;
        cachedCapsMode = 0;
    }

    private void updateCachedCapsMode() {
        if (editorInfo == null) { cachedCapsMode = 0; return; }
        int inputClass = editorInfo.inputType & InputType.TYPE_MASK_CLASS;
        int variation = editorInfo.inputType & InputType.TYPE_MASK_VARIATION;
        if (inputClass != InputType.TYPE_CLASS_TEXT
                || variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
            cachedCapsMode = 0;
            return;
        }
        int mode = editorInfo.initialCapsMode;
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            try {
                mode = connection.getCursorCapsMode(TextUtils.CAP_MODE_CHARACTERS
                        | TextUtils.CAP_MODE_WORDS | TextUtils.CAP_MODE_SENTENCES);
            } catch (RuntimeException ignored) { }
        }
        cachedCapsMode = mode;
    }

    private void applyEditorConfiguration() {
        if (webView == null || !pageReady) return;
        int type = editorInfo == null ? InputType.TYPE_CLASS_TEXT : editorInfo.inputType;
        int options = editorInfo == null ? EditorInfo.IME_ACTION_UNSPECIFIED : editorInfo.imeOptions;
        updateCachedCapsMode();
        webView.evaluateJavascript("if(window.configureInput)window.configureInput("
                + type + "," + options + "," + cachedCapsMode + ");", null);
    }

    // Haptics only. Key sounds are played by KeySoundEngine (the generic Android click is no longer used).
    private void playHaptic() {
        if (webView == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences("keyboard_settings", MODE_PRIVATE);
        if (prefs.getBoolean("vibration_enabled", true)) {
            int feedback = Build.VERSION.SDK_INT >= 27
                    ? HapticFeedbackConstants.KEYBOARD_TAP : HapticFeedbackConstants.VIRTUAL_KEY;
            webView.performHapticFeedback(feedback);
        }
    }

    private void playKeySound(String kind) {
        KeySoundEngine engine = soundEngine;
        if (engine != null) engine.playKind(kind);
    }

    private void deleteBeforeCursor() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        CharSequence selected = connection.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            connection.commitText("", 1);
            return;
        }
        if (Build.VERSION.SDK_INT >= 24) {
            connection.deleteSurroundingTextInCodePoints(1, 0);
        } else {
            connection.deleteSurroundingText(1, 0);
        }
    }

    private void insertSpace(boolean replacePreviousSpace) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        if (replacePreviousSpace && editorInfo != null) {
            int inputClass = editorInfo.inputType & InputType.TYPE_MASK_CLASS;
            int variation = editorInfo.inputType & InputType.TYPE_MASK_VARIATION;
            boolean plainText = inputClass == InputType.TYPE_CLASS_TEXT
                    && variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    && variation != InputType.TYPE_TEXT_VARIATION_URI
                    && variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                    && variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
                    && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
            if (plainText) {
                try {
                    CharSequence before = connection.getTextBeforeCursor(2, 0);
                    if (before != null && before.length() >= 2
                            && before.charAt(before.length() - 1) == ' '
                            && Character.isLetterOrDigit(before.charAt(before.length() - 2))) {
                        connection.deleteSurroundingText(1, 0);
                        connection.commitText(". ", 1);
                        return;
                    }
                } catch (RuntimeException ignored) {
                    // If surrounding text is unavailable, insert a normal space.
                }
            }
        }
        connection.commitText(" ", 1);
    }

    // ------------------------------------------------------------------
    // Space-bar trackpad: iPhone-style four-direction cursor control
    // ------------------------------------------------------------------
    // keyboard.html reports finger deltas (cursorBegin / cursorMove / cursorEnd). Each step is performed
    // by the best mechanism the host allows:
    //  1. DPAD_LEFT/RIGHT/UP/DOWN key events through the InputConnection. The host editor then moves the
    //     caret with its own text layout, so wrapped lines, bidi (RTL / mixed) order and grapheme
    //     clusters are handled by the editor itself. This is the only way to get genuine visual movement,
    //     because CursorAnchorInfo exposes glyph bounds only for composing text, not for arbitrary text.
    //  2. If the host demonstrably ignores key events (no selection change reported), or the developer
    //     sets the "trackpad_use_key_events" preference to false: setSelection() with grapheme-safe
    //     logical steps. Horizontal direction uses the caret run direction (CursorAnchorInfo RTL flag when
    //     fresh, else a strong-character heuristic). Vertical movement then follows newline-separated
    //     lines only; visually wrapped lines are NOT supported in this mode.
    // CursorAnchorInfo monitoring is enabled only while a gesture runs and is always switched off again.

    private void beginCursorTrack() {
        stopAnchorMonitoring();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || editorInfo == null || editorInfo.inputType == InputType.TYPE_NULL) {
            trackActive = false;
            return;
        }
        int type = editorInfo.inputType;
        trackActive = true;
        trackAccX = 0f;
        trackAccY = 0f;
        trackPos = -1;
        lastMoveAt = 0;
        lastAnchor = null;
        trackMultiLine = (type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && (type & (InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE)) != 0;
        trackKeys = getSharedPreferences("keyboard_settings", MODE_PRIVATE)
                .getBoolean("trackpad_use_key_events", true);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                trackMonitoring = ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE
                        | InputConnection.CURSOR_UPDATE_MONITOR);
            } catch (RuntimeException e) {
                trackMonitoring = false;
            }
        }
    }

    private void stopAnchorMonitoring() {
        if (!trackMonitoring) return;
        trackMonitoring = false;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || Build.VERSION.SDK_INT < 21) return;
        try {
            ic.requestCursorUpdates(0);
        } catch (RuntimeException ignored) { }
    }

    private void stopCursorTrack(boolean refresh) {
        boolean was = trackActive;
        trackActive = false;
        synchronized (trackLock) {
            pendingDx = 0f;
            pendingDy = 0f;
            drainScheduled = false;
        }
        mainHandler.removeCallbacks(drainRunnable);
        trackAccX = 0f;
        trackAccY = 0f;
        trackPos = -1;
        stopAnchorMonitoring();
        lastAnchor = null;
        if (was && refresh) {
            updateCachedCapsMode();
            if (webView != null && pageReady) {
                webView.evaluateJavascript("if(window.refreshCaps)window.refreshCaps();", null);
            }
        }
    }

    @Override public void onUpdateCursorAnchorInfo(CursorAnchorInfo info) {
        super.onUpdateCursorAnchorInfo(info);
        if (info == null) return;
        lastAnchor = info;
        anchorAt = SystemClock.uptimeMillis();
        noteSelectionEcho(info.getSelectionStart(), info.getSelectionEnd());
    }

    private void scheduleDrain(long delayMs) {
        synchronized (trackLock) {
            if (drainScheduled) return;
            drainScheduled = true;
        }
        mainHandler.postDelayed(drainRunnable, delayMs);
    }

    private void drainTrack() {
        float dx, dy;
        synchronized (trackLock) {
            dx = pendingDx;
            dy = pendingDy;
            pendingDx = 0f;
            pendingDy = 0f;
            drainScheduled = false;
        }
        if (!trackActive) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) { stopCursorTrack(false); return; }
        try {
            checkProbe();
            trackAccX = Math.max(-40f * TRACK_H_PX, Math.min(40f * TRACK_H_PX, trackAccX + dx));
            trackAccY = trackMultiLine ? Math.max(-8f * TRACK_V_PX, Math.min(8f * TRACK_V_PX, trackAccY + dy)) : 0f;
            // Vertical: at most one visual line per pass, so the boundary checks always see fresh state.
            int vs = (int) (trackAccY / TRACK_V_PX);
            if (vs != 0) {
                int dir = vs > 0 ? 1 : -1;
                trackAccY -= dir * TRACK_V_PX;
                moveVertical(ic, dir);
            }
            int hs = (int) (trackAccX / TRACK_H_PX);
            if (hs != 0) {
                hs = Math.max(-TRACK_MAX_H_STEPS, Math.min(TRACK_MAX_H_STEPS, hs));
                trackAccX -= hs * TRACK_H_PX;
                moveHorizontal(ic, hs);
            }
            if (Math.abs(trackAccY) >= TRACK_V_PX || Math.abs(trackAccX) >= TRACK_H_PX) {
                scheduleDrain(TRACK_CARRY_MS);
            }
        } catch (RuntimeException ignored) {
            // Host connection died mid-gesture; the next lifecycle callback cleans up.
        }
    }

    private boolean useKeyEvents() {
        return trackKeys && dpadState != -1;
    }

    // ---- key-event mode (host-native visual movement) ----

    private void moveHorizontal(InputConnection ic, int steps) {
        boolean visualRight = steps > 0;
        int n = Math.abs(steps);
        if (!useKeyEvents()) {
            logicalHorizontal(ic, visualRight, n);
            lastMoveAt = SystemClock.uptimeMillis();
            return;
        }
        int w = Math.min(TRACK_MAX_WINDOW, n * 8 + 8);
        CharSequence bc = ic.getTextBeforeCursor(w, 0);
        CharSequence ac = ic.getTextAfterCursor(w, 0);
        int allowed = n;
        boolean hasSel = selStart >= 0 && selEnd >= 0 && selStart != selEnd;
        if (hasSel) {
            allowed = 1; // the first key press only collapses the selection
        } else if (bc != null && ac != null) {
            // Never send a key the host cannot act on: an unconsumed DPAD key can move focus to another field.
            String before = bc.toString();
            String text = before + ac;
            int cur = before.length();
            boolean rtl = caretRtl(text, cur);
            boolean forward = visualRight != rtl; // logical direction of the requested visual step
            int[] done = new int[1];
            walk(text, cur, forward, n, done);
            int avail = done[0];
            // At a logical text edge the caret may still move visually if it sits in a run whose
            // direction differs from the paragraph's (mixed Kurdish / English): allow one step.
            if (avail == 0 && paragraphRtl(text, cur) != rtl) avail = 1;
            allowed = Math.min(n, avail);
        } else {
            allowed = 1; // text not readable: cannot guard, move one step
        }
        if (allowed > 0) {
            sendKey(ic, visualRight ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT, allowed);
            armProbe();
        }
        lastMoveAt = SystemClock.uptimeMillis();
    }

    private void moveVertical(InputConnection ic, int dir) {
        if (!useKeyEvents()) {
            logicalVertical(ic, dir);
            lastMoveAt = SystemClock.uptimeMillis();
            return;
        }
        boolean hasSel = selStart >= 0 && selEnd >= 0 && selStart != selEnd;
        if (!hasSel) {
            CharSequence edge = dir < 0 ? ic.getTextBeforeCursor(1, 0) : ic.getTextAfterCursor(1, 0);
            if (edge != null && edge.length() == 0) { lastMoveAt = SystemClock.uptimeMillis(); return; } // already at the text edge
        }
        sendKey(ic, dir < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, 1);
        armProbe();
        lastMoveAt = SystemClock.uptimeMillis();
    }

    private void sendKey(InputConnection ic, int keyCode, int count) {
        trackPos = -1; // the caret moved behind the logical fallback's back
        for (int i = 0; i < count; i++) {
            long t = SystemClock.uptimeMillis();
            int flags = KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE;
            ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags));
            ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags));
        }
    }

    // Detects hosts that ignore key events: a key was sent but no selection change was ever reported.
    private void armProbe() {
        if (dpadState != 0 || probePending) return;
        probePending = true;
        probeStart = selStart;
        probeEnd = selEnd;
        probeAt = SystemClock.uptimeMillis();
    }

    private void noteSelectionEcho(int start, int end) {
        if (probePending && (start != probeStart || end != probeEnd)) {
            probePending = false;
            probeMisses = 0;
            dpadState = 1;
        }
    }

    private void checkProbe() {
        if (dpadState != 0 || !probePending) return;
        if (SystemClock.uptimeMillis() - probeAt > TRACK_PROBE_MS) {
            probePending = false;
            if (++probeMisses >= 2) dpadState = -1;
        }
    }

    // ---- logical fallback (setSelection) ----

    private void logicalHorizontal(InputConnection ic, boolean visualRight, int n) {
        if (collapseSelection(ic, visualRight ? 1 : -1)) {
            n--;
            if (n <= 0) return;
        }
        if (!ensureTrackPos(ic)) return;
        int w = Math.min(TRACK_MAX_WINDOW, n * 8 + 8);
        CharSequence bc = ic.getTextBeforeCursor(w, 0);
        CharSequence ac = ic.getTextAfterCursor(w, 0);
        if (bc == null || ac == null) return;
        String before = bc.toString();
        String text = before + ac;
        int cur = before.length();
        int idx = cur;
        for (int i = 0; i < n; i++) {
            boolean rtl = i == 0 ? caretRtl(text, idx) : strongRtlNear(text, idx);
            int next = step(text, idx, visualRight != rtl);
            if (next == idx) break;
            idx = next;
        }
        if (idx != cur) setCaret(ic, trackPos + (idx - cur));
    }

    private void logicalVertical(InputConnection ic, int dir) {
        if (collapseSelection(ic, dir)) return;
        if (!ensureTrackPos(ic)) return;
        CharSequence bc = ic.getTextBeforeCursor(TRACK_LINE_WINDOW, 0);
        CharSequence ac = ic.getTextAfterCursor(TRACK_LINE_WINDOW, 0);
        if (bc == null || ac == null) return;
        String before = bc.toString();
        String text = before + ac;
        int cur = before.length();
        int ls = before.lastIndexOf('\n') + 1;
        if (ls == 0 && cur >= TRACK_LINE_WINDOW) return; // line start lies outside the window
        int col = clustersBetween(text, ls, cur);
        int target;
        if (dir < 0) {
            if (ls == 0) {
                target = 0; // first line: go to the start of the text
            } else {
                int prevEnd = ls - 1;
                int prevStart = before.lastIndexOf('\n', prevEnd - 1) + 1;
                if (prevStart == 0 && cur >= TRACK_LINE_WINDOW) return;
                target = walkLine(text, prevStart, prevEnd, col);
            }
        } else {
            int nl = text.indexOf('\n', cur);
            if (nl < 0) {
                if (ac.length() >= TRACK_LINE_WINDOW) return;
                target = text.length(); // last line: go to the end of the text
            } else {
                int nextStart = nl + 1;
                int nextEnd = text.indexOf('\n', nextStart);
                if (nextEnd < 0) nextEnd = text.length();
                target = walkLine(text, nextStart, nextEnd, col);
            }
        }
        if (target != cur) setCaret(ic, trackPos + (target - cur));
    }

    private boolean collapseSelection(InputConnection ic, int dir) {
        if (selStart < 0 || selEnd < 0 || selStart == selEnd) return false;
        setCaret(ic, dir < 0 ? Math.min(selStart, selEnd) : Math.max(selStart, selEnd));
        return true;
    }

    private void setCaret(InputConnection ic, int pos) {
        pos = Math.max(0, pos);
        ic.setSelection(pos, pos);
        trackPos = pos;
        selStart = pos;
        selEnd = pos;
    }

    private boolean ensureTrackPos(InputConnection ic) {
        if (trackPos < 0) {
            int caret = resolveCaret(ic);
            trackPos = caret >= 0 ? caret : (selStart >= 0 && selStart == selEnd ? selStart : -1);
        }
        return trackPos >= 0;
    }

    /** Absolute caret index straight from the editor (used once, only by the logical fallback). */
    private int resolveCaret(InputConnection ic) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                SurroundingText st = ic.getSurroundingText(1, 1, 0);
                if (st != null && st.getOffset() >= 0) return st.getOffset() + st.getSelectionStart();
            }
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.token = 0;
            request.hintMaxChars = 8;
            ExtractedText x = ic.getExtractedText(request, 0);
            if (x != null) return x.startOffset + Math.min(x.selectionStart, x.selectionEnd);
        } catch (RuntimeException ignored) { }
        return -1;
    }

    // ---- text helpers (grapheme clusters, direction) ----

    private BreakIterator graphemeIterator() {
        if (graphemes == null) graphemes = BreakIterator.getCharacterInstance();
        return graphemes;
    }

    /** Walks up to max grapheme clusters (never splits surrogate pairs, combining marks, ZWNJ/ZWJ sequences). */
    private int walk(String text, int from, boolean forward, int max, int[] stepsOut) {
        int pos = from, n = 0;
        if (!text.isEmpty()) {
            BreakIterator it = graphemeIterator();
            it.setText(text);
            while (n < max) {
                int next = forward ? it.following(pos) : it.preceding(pos);
                if (next == BreakIterator.DONE || next == pos) break;
                pos = next;
                n++;
            }
        }
        if (stepsOut != null) stepsOut[0] = n;
        return pos;
    }

    private int step(String text, int pos, boolean forward) {
        return walk(text, pos, forward, 1, null);
    }

    private int clustersBetween(String text, int from, int to) {
        int pos = from, n = 0;
        if (from >= to || text.isEmpty()) return 0;
        BreakIterator it = graphemeIterator();
        it.setText(text);
        while (pos < to) {
            int next = it.following(pos);
            if (next == BreakIterator.DONE || next <= pos) break;
            pos = next;
            n++;
        }
        return n;
    }

    private int walkLine(String text, int start, int end, int columns) {
        int pos = start;
        for (int k = 0; k < columns && pos < end; k++) {
            int next = step(text, pos, true);
            if (next <= pos || next > end) break;
            pos = next;
        }
        return pos;
    }

    private static int strongDir(int cp) {
        byte d = Character.getDirectionality(cp);
        if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return 1;
        if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return 0;
        return -1;
    }

    /** Direction of the text run at the caret: nearest strong character, preferring the one before it. */
    private static boolean strongRtlNear(String text, int idx) {
        int i = idx;
        while (i > 0 && idx - i < 48) {
            int cp = text.codePointBefore(i);
            i -= Character.charCount(cp);
            int d = strongDir(cp);
            if (d >= 0) return d == 1;
        }
        i = idx;
        while (i < text.length() && i - idx < 48) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int d = strongDir(cp);
            if (d >= 0) return d == 1;
        }
        return false;
    }

    /** Paragraph base direction: first strong character of the paragraph containing idx (like TextView). */
    private static boolean paragraphRtl(String text, int idx) {
        int i = text.lastIndexOf('\n', idx - 1) + 1;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (cp == '\n') break;
            i += Character.charCount(cp);
            int d = strongDir(cp);
            if (d >= 0) return d == 1;
        }
        return false;
    }

    /** True if the caret sits in an RTL run. Uses the host's own CursorAnchorInfo flag when it is newer than our last move. */
    private boolean caretRtl(String text, int idx) {
        CursorAnchorInfo a = lastAnchor;
        if (a != null && trackMonitoring && anchorAt >= lastMoveAt
                && !Float.isNaN(a.getInsertionMarkerHorizontal())) {
            return (a.getInsertionMarkerFlags() & CursorAnchorInfo.FLAG_IS_RTL) != 0;
        }
        return strongRtlNear(text, idx);
    }

    /** Keyboard height in pixels. extraCssPx is extra height (in 390-wide CSS px) used while a manager form is open. */
    private int keyboardHeightPx(int extraCssPx) {
        return (int) ((283.2f + extraCssPx) * getResources().getDisplayMetrics().widthPixels / 390f + 0.5f);
    }

    // ------------------------------------------------------------------
    // Password & Text Manager (storage + insertion helpers)
    // ------------------------------------------------------------------

    private synchronized RecordStore passwordStore() {
        if (passwordStore == null) {
            secureCodec = new SecureCodec();
            passwordStore = new RecordStore(new File(getFilesDir(), "passwords.enc"), secureCodec,
                    new String[]{"id", "email", "pass"}, new int[]{200, 500, 1000});
        }
        return passwordStore;
    }

    private synchronized RecordStore textStore() {
        if (textStore == null) {
            textStore = new RecordStore(new File(getFilesDir(), "texts.json"), RecordStore.PLAIN,
                    new String[]{"id", "text"}, new int[]{200, 20000});
        }
        return textStore;
    }

    private boolean storageUnlocked() {
        if (Build.VERSION.SDK_INT >= 24) {
            UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
            return um == null || um.isUserUnlocked();
        }
        return true;
    }

    private static String okJson() { return "{\"ok\":true}"; }

    private static String errorJson(String code) {
        return "{\"ok\":false,\"error\":" + JSONObject.quote(code) + "}";
    }

    /** Password records are listed WITHOUT the password itself; only whether one is stored. */
    private static JSONArray summaries(boolean secret, List<JSONObject> records) throws JSONException {
        JSONArray array = new JSONArray();
        for (JSONObject r : records) {
            if (secret) {
                JSONObject s = new JSONObject();
                s.put("uid", r.optString("uid"));
                s.put("id", r.optString("id"));
                s.put("email", r.optString("email"));
                s.put("hasPass", !r.optString("pass").isEmpty());
                array.put(s);
            } else {
                array.put(r);
            }
        }
        return array;
    }

    private String managerList(boolean secret) {
        if (!storageUnlocked()) return errorJson("locked");
        try {
            RecordStore store = secret ? passwordStore() : textStore();
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("records", summaries(secret, store.all()));
            return out.toString();
        } catch (RecordStore.StoreException e) {
            return errorJson(e.code);
        } catch (JSONException | RuntimeException e) {
            return errorJson("failed");
        }
    }

    private String managerGet(String uid) {
        if (uid == null) return errorJson("invalid");
        if (!storageUnlocked()) return errorJson("locked");
        try {
            JSONObject record = passwordStore().get(uid);
            if (record == null) return errorJson("missing");
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("record", record);
            return out.toString();
        } catch (RecordStore.StoreException e) {
            return errorJson(e.code);
        } catch (JSONException | RuntimeException e) {
            return errorJson("failed");
        }
    }

    private String managerSave(boolean secret, String json) {
        if (json == null) return errorJson("invalid");
        if (!storageUnlocked()) return errorJson("locked");
        try {
            RecordStore store = secret ? passwordStore() : textStore();
            JSONObject saved = store.upsert(new JSONObject(json));
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("uid", saved.optString("uid"));
            out.put("records", summaries(secret, store.all()));
            return out.toString();
        } catch (RecordStore.StoreException e) {
            return errorJson(e.code);
        } catch (JSONException | RuntimeException e) {
            return errorJson("invalid");
        }
    }

    private String managerDelete(boolean secret, String uid) {
        if (uid == null) return errorJson("invalid");
        if (!storageUnlocked()) return errorJson("locked");
        try {
            RecordStore store = secret ? passwordStore() : textStore();
            store.delete(uid);
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("records", summaries(secret, store.all()));
            return out.toString();
        } catch (RecordStore.StoreException e) {
            return errorJson(e.code);
        } catch (JSONException | RuntimeException e) {
            return errorJson("failed");
        }
    }

    private String managerMove(boolean secret, String uidsJson) {
        if (uidsJson == null) return errorJson("invalid");
        if (!storageUnlocked()) return errorJson("locked");
        try {
            JSONArray array = new JSONArray(uidsJson);
            List<String> order = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) order.add(array.getString(i));
            RecordStore store = secret ? passwordStore() : textStore();
            store.reorder(order);
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("records", summaries(secret, store.all()));
            return out.toString();
        } catch (RecordStore.StoreException e) {
            return errorJson(e.code);
        } catch (JSONException | RuntimeException e) {
            return errorJson("invalid");
        }
    }

    /** Looks up the value on the calling thread, then commits it through the current InputConnection. */
    private String managerInsert(boolean secret, String uid, String field) {
        if (uid == null) return errorJson("invalid");
        if (!storageUnlocked()) return errorJson("locked");
        String value;
        try {
            JSONObject record = (secret ? passwordStore() : textStore()).get(uid);
            if (record == null) return errorJson("missing");
            if (secret) {
                if ("email".equals(field)) value = record.optString("email");
                else if ("pass".equals(field)) value = record.optString("pass");
                else return errorJson("invalid");
            } else {
                value = record.optString("text");
            }
        } catch (RecordStore.StoreException e) {
            return errorJson(e.code);
        } catch (RuntimeException e) {
            return errorJson("failed");
        }
        if (value.isEmpty()) return errorJson("empty");
        return commitToHost(value);
    }

    private String commitToHost(final String value) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean committed = new AtomicBoolean(false);
        mainHandler.post(() -> {
            try {
                InputConnection connection = getCurrentInputConnection();
                if (connection != null) {
                    connection.commitText(value, 1);
                    committed.set(true);
                }
            } catch (RuntimeException ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return committed.get() ? okJson() : errorJson("no_input");
    }

    // ---- Clipboard for the Password & Text Manager copy / paste icons --------------
    // keyboard.html is loaded from file://, where navigator.clipboard does not exist, so the
    // page calls these through the bridge. No permission is needed: the active input method
    // is allowed to read the clipboard (Android 10+). Runs on the main thread, like commitToHost().
    private String clipboardCopy(final String value, final boolean sensitive) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean copied = new AtomicBoolean(false);
        mainHandler.post(() -> {
            try {
                ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (manager != null) {
                    ClipData clip = ClipData.newPlainText("Bellamie", value);
                    if (sensitive && Build.VERSION.SDK_INT >= 33) {
                        // Asks Android 13+ not to show the copied password in the clipboard preview.
                        PersistableBundle extras = new PersistableBundle();
                        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                        clip.getDescription().setExtras(extras);
                    }
                    manager.setPrimaryClip(clip);
                    copied.set(true);
                }
            } catch (RuntimeException ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return copied.get() ? okJson() : errorJson("clip_failed");
    }

    private String clipboardPaste() {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean reached = new AtomicBoolean(false);
        final AtomicReference<String> text = new AtomicReference<>("");
        mainHandler.post(() -> {
            try {
                ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (manager != null) {
                    reached.set(true);
                    ClipData clip = manager.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        // Plain text only: an image/URI clip must not paste its URI string.
                        CharSequence value = clip.getItemAt(0).getText();
                        if (value != null) text.set(value.toString());
                    }
                }
            } catch (RuntimeException ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!reached.get()) return errorJson("clip_failed");
        String pasted = text.get();
        if (pasted.isEmpty()) return errorJson("clip_empty");
        return "{\"ok\":true,\"text\":" + JSONObject.quote(pasted) + "}";
    }

    private final class KeyboardBridge {
        @JavascriptInterface public void commit(String value) {
            if (value == null) return;
            mainHandler.post(() -> {
                InputConnection connection = getCurrentInputConnection();
                if (connection != null) connection.commitText(value, 1);
            });
        }
        @JavascriptInterface public void delete() {
            mainHandler.post(BellamieInputMethodService.this::deleteBeforeCursor);
        }
        // Legacy call (Password & Text Manager controls): haptic + the Shift-ON fallback sound, exactly one sound.
        @JavascriptInterface public void feedback() {
            playKeySound("fallback");
            mainHandler.post(BellamieInputMethodService.this::playHaptic);
        }
        // Key touch-down: haptic (unchanged) + the recorded sound for `kind` ("silent" = haptic only).
        // Sound is played on this bridge thread directly to avoid an extra main-thread hop.
        @JavascriptInterface public void keyDown(String kind) {
            playKeySound(kind);
            mainHandler.post(BellamieInputMethodService.this::playHaptic);
        }
        // Sound only, no haptic (confirmed Space tap, emoji taps, Backspace hold ticks).
        @JavascriptInterface public void keySound(String kind) {
            playKeySound(kind);
        }
        @JavascriptInterface public void pickImage() {
            mainHandler.post(() -> {
                Intent intent = new Intent(BellamieInputMethodService.this, ImagePickerActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            });
        }
        @JavascriptInterface public void insertImage(String value) {
            if (value == null || Build.VERSION.SDK_INT < 25) return;
            mainHandler.post(() -> {
                try {
                    Uri uri = Uri.parse(value);
                    String mime = getContentResolver().getType(uri);
                    if (mime == null || !mime.startsWith("image/")) return;
                    InputConnection connection = getCurrentInputConnection();
                    if (connection == null) return;
                    InputContentInfo content = new InputContentInfo(uri,
                            new ClipDescription("Bellamie sticker", new String[]{mime}), null);
                    connection.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null);
                } catch (RuntimeException ignored) { }
            });
        }
        @JavascriptInterface public void voice(boolean kurdish) {
            mainHandler.post(() -> {
                Intent intent = new Intent(BellamieInputMethodService.this, VoiceInputActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra("kurdish", kurdish);
                startActivity(intent);
            });
        }
        @JavascriptInterface public void space(boolean replacePreviousSpace) {
            mainHandler.post(() -> insertSpace(replacePreviousSpace));
        }
        // ---- Space-bar trackpad (see "Space-bar trackpad" above) ----
        @JavascriptInterface public void cursorBegin() {
            synchronized (trackLock) { pendingDx = 0f; pendingDy = 0f; }
            mainHandler.post(BellamieInputMethodService.this::beginCursorTrack);
        }
        @JavascriptInterface public void cursorMove(double dx, double dy) {
            if (Double.isNaN(dx) || Double.isNaN(dy) || Double.isInfinite(dx) || Double.isInfinite(dy)) return;
            synchronized (trackLock) {
                pendingDx += (float) dx;
                pendingDy += (float) dy;
                if (drainScheduled) return; // coalesce: one pending drain handles all deltas received meanwhile
                drainScheduled = true;
            }
            mainHandler.post(drainRunnable);
        }
        @JavascriptInterface public void cursorEnd() {
            synchronized (trackLock) { pendingDx = 0f; pendingDy = 0f; }
            mainHandler.post(() -> stopCursorTrack(true));
        }
        @JavascriptInterface public void enter() {
            mainHandler.post(() -> {
                InputConnection connection = getCurrentInputConnection();
                if (connection == null) return;
                int action = editorInfo == null ? EditorInfo.IME_ACTION_NONE
                        : editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
                boolean multiLine = editorInfo != null
                        && (editorInfo.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
                if (!multiLine && action != EditorInfo.IME_ACTION_NONE
                        && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    connection.performEditorAction(action);
                } else {
                    connection.commitText("\n", 1);
                    if (webView != null && pageReady) {
                        webView.evaluateJavascript("if(window.onNewLine)window.onNewLine();", null);
                    }
                }
            });
        }
        // ---- Password & Text Manager bridge ---------------------------------------
        // Passwords: the list never contains the password itself. pwGet() returns one full
        // record (for editing / the eye icon); pwInsert() commits Email or Password directly
        // into the focused field without the password passing through JavaScript at all.
        @JavascriptInterface public String pwList() { return managerList(true); }
        @JavascriptInterface public String pwGet(String uid) { return managerGet(uid); }
        @JavascriptInterface public String pwSave(String json) { return managerSave(true, json); }
        @JavascriptInterface public String pwDelete(String uid) { return managerDelete(true, uid); }
        @JavascriptInterface public String pwMove(String uidsJson) { return managerMove(true, uidsJson); }
        @JavascriptInterface public String pwInsert(String uid, String field) { return managerInsert(true, uid, field); }
        @JavascriptInterface public String pwReset() {
            try {
                passwordStore().reset();
                if (secureCodec != null) secureCodec.deleteKey();
                return okJson();
            } catch (RuntimeException e) {
                return errorJson("failed");
            }
        }
        @JavascriptInterface public String txtList() { return managerList(false); }
        @JavascriptInterface public String txtSave(String json) { return managerSave(false, json); }
        @JavascriptInterface public String txtDelete(String uid) { return managerDelete(false, uid); }
        @JavascriptInterface public String txtMove(String uidsJson) { return managerMove(false, uidsJson); }
        @JavascriptInterface public String txtInsert(String uid) { return managerInsert(false, uid, null); }
        // Grows / restores the keyboard window while the add/edit form or search is open, so the
        // normal keys can sit below the form. 0 restores the standard keyboard height.
        @JavascriptInterface public void setExtraHeight(int extraCssPx) {
            final int extra = Math.max(0, Math.min(extraCssPx, 300));
            mainHandler.post(() -> {
                if (webView == null) return;
                ViewGroup.LayoutParams params = webView.getLayoutParams();
                int height = keyboardHeightPx(extra);
                if (params != null && params.height != height) {
                    params.height = height;
                    webView.setLayoutParams(params);
                }
            });
        }
        // Copy / paste icons inside the Password & Text Manager fields (see clipboardCopy/Paste).
        @JavascriptInterface public String copyText(String value, boolean sensitive) {
            if (value == null || value.isEmpty()) return errorJson("empty");
            return clipboardCopy(value, sensitive);
        }
        @JavascriptInterface public String pasteText() { return clipboardPaste(); }
        @JavascriptInterface public int capsMode() { return cachedCapsMode; }
        // Key popup that would be clipped by the WebView's top edge (first row): drawn natively.
        @JavascriptInterface public void showKeyPopup(String json) {
            if (json == null) return;
            mainHandler.post(() -> {
                KeyPopupOverlay overlay = popupOverlay;
                if (overlay != null && webView != null) overlay.show(json);
            });
        }
        @JavascriptInterface public void hideKeyPopup(int id) {
            mainHandler.post(() -> {
                KeyPopupOverlay overlay = popupOverlay;
                if (overlay != null) overlay.hide(id);
            });
        }
    }
}
