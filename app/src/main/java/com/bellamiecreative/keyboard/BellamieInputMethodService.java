package com.bellamiecreative.keyboard;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.view.inputmethod.InputConnection;

public class BellamieInputMethodService extends InputMethodService {
    private WebView webView;

    @Override public View onCreateInputView() {
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        webView.setBackgroundColor(0xFFD0D4D9);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new KeyboardBridge(), "AndroidKeyboard");
        int keyboardHeight = (int) (283.2f * getResources().getDisplayMetrics().widthPixels / 390f + 0.5f);
        webView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight));
        webView.loadUrl("file:///android_asset/keyboard.html");
        return webView;
    }

    @Override public void onStartInputView(android.view.inputmethod.EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (webView != null) webView.evaluateJavascript("if(window.resetForInput)window.resetForInput();", null);
    }

    private final class KeyboardBridge {
        @JavascriptInterface public void commit(String value) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null && value != null) ic.commitText(value, 1);
        }
        @JavascriptInterface public void delete() {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.deleteSurroundingText(1, 0);
        }
        @JavascriptInterface public void enter() {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                int action = getCurrentInputEditorInfo() == null ? 0 : getCurrentInputEditorInfo().imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
                if (action != android.view.inputmethod.EditorInfo.IME_ACTION_NONE && action != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action);
                } else {
                    ic.commitText("\n", 1);
                }
            }
        }
    }
}
