package com.bellamiecreative.keyboard;

import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.HapticFeedbackConstants;
import android.view.SoundEffectConstants;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class BellamieInputMethodService extends InputMethodService {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private EditorInfo editorInfo;
    private volatile int cachedCapsMode;
    private boolean pageReady;

    @Override public View onCreateInputView() {
        pageReady = false;
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
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
                applyEditorConfiguration();
                view.evaluateJavascript("if(window.resetForInput)window.resetForInput();", null);
            }
        });
        webView.addJavascriptInterface(new KeyboardBridge(), "AndroidKeyboard");
        int keyboardHeight = (int) (283.2f * getResources().getDisplayMetrics().widthPixels / 390f + 0.5f);
        webView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight));
        webView.loadUrl("file:///android_asset/keyboard.html");
        return webView;
    }

    @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        editorInfo = attribute;
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
        updateCachedCapsMode();
        if (webView != null && pageReady) {
            webView.evaluateJavascript("if(window.refreshCaps)window.refreshCaps();", null);
        }
    }

    @Override public void onFinishInput() {
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

    private void playKeyFeedback() {
        if (webView == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences("keyboard_settings", MODE_PRIVATE);
        if (prefs.getBoolean("vibration_enabled", true)) {
            int feedback = Build.VERSION.SDK_INT >= 27
                    ? HapticFeedbackConstants.KEYBOARD_TAP : HapticFeedbackConstants.VIRTUAL_KEY;
            webView.performHapticFeedback(feedback);
        }
        if (prefs.getBoolean("sound_enabled", true)) {
            webView.playSoundEffect(SoundEffectConstants.CLICK);
        }
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
        @JavascriptInterface public void feedback() {
            mainHandler.post(BellamieInputMethodService.this::playKeyFeedback);
        }
        @JavascriptInterface public void space(boolean replacePreviousSpace) {
            mainHandler.post(() -> insertSpace(replacePreviousSpace));
        }
        @JavascriptInterface public void enter() {
            mainHandler.post(() -> {
                InputConnection connection = getCurrentInputConnection();
                if (connection == null) return;
                int action = editorInfo == null ? EditorInfo.IME_ACTION_NONE
                        : editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
                if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    connection.performEditorAction(action);
                } else {
                    connection.commitText("\n", 1);
                }
            });
        }
        @JavascriptInterface public int capsMode() { return cachedCapsMode; }
    }
}
