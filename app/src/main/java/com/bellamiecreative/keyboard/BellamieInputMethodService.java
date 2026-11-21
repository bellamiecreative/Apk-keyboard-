package com.bellamiecreative.keyboard;

import android.inputmethodservice.InputMethodService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ClipDescription;
import android.net.Uri;
import android.view.inputmethod.InputContentInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.HapticFeedbackConstants;
import android.view.SoundEffectConstants;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BellamieInputMethodService extends InputMethodService {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private EditorInfo editorInfo;
    private volatile int cachedCapsMode;
    private boolean pageReady;
    private boolean voiceReceiverRegistered;
    private boolean imageReceiverRegistered;
    // Password & Text Manager storage (created lazily on first use)
    private SecureCodec secureCodec;
    private RecordStore passwordStore;
    private RecordStore textStore;
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
    }

    @Override public void onDestroy() {
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
        webView = new WebView(this);
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
        @JavascriptInterface public int capsMode() { return cachedCapsMode; }
    }
}
