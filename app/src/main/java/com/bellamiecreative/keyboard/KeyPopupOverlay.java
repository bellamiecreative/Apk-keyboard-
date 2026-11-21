package com.bellamiecreative.keyboard;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.widget.PopupWindow;

import org.json.JSONObject;

/**
 * Draws first-row key previews outside the WebView bounds.
 * The short hide delay mirrors the HTML popup's 80 ms removal delay.
 */
public class KeyPopupOverlay {

    private static final String TAG = "KeyPopupOverlay";
    private static final long HIDE_DELAY_MS = 80L;
    private final View anchor;
    private final SparseArray<Popup> popups = new SparseArray<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    public KeyPopupOverlay(View anchorWebView) {
        this.anchor = anchorWebView;
    }

    /** JSON values are CSS px relative to the WebView's top-left. */
    public void show(String json) {
        try {
            if (anchor == null || anchor.getWindowToken() == null) return;
            JSONObject o = new JSONObject(json);
            int id = o.optInt("id");
            String text = o.optString("text", "");
            if (text.length() == 0) {
                hide(id);
                return;
            }

            float s = (float) o.optDouble("s", 1);
            float vw = (float) o.optDouble("vw", 0);
            float px = (vw > 0 && anchor.getWidth() > 0)
                    ? anchor.getWidth() / vw
                    : (float) o.optDouble("dpr",
                    anchor.getResources().getDisplayMetrics().density);

            float l = (float) o.getDouble("l") * px;
            float t = (float) o.getDouble("t") * px;
            float w = (float) o.getDouble("w") * px;
            float h = (float) o.getDouble("h") * px;
            float sx = (float) o.getDouble("sx") * px;
            float sw = (float) o.getDouble("sw") * px;
            float sh = (float) o.getDouble("sh") * px;

            Popup p = popups.get(id);
            if (p == null) {
                p = new Popup(anchor.getContext());
                popups.put(id, p);
            }

            // If the same touch is still active, cancel its pending dismissal.
            if (p.pendingHide != null) {
                handler.removeCallbacks(p.pendingHide);
                p.pendingHide = null;
            }

            p.view.set(text, w, h, sx, sw, sh, s * px);

            int[] loc = new int[2];
            if (Build.VERSION.SDK_INT >= 29) {
                anchor.getLocationOnScreen(loc);
            } else {
                anchor.getLocationInWindow(loc);
            }

            int pad = p.view.pad;
            int x = Math.round(loc[0] + l) - pad;
            int y = Math.round(loc[1] + t) - pad;
            int totalW = p.view.totalW();
            int totalH = p.view.totalH();

            if (p.window.isShowing()) {
                p.window.update(x, y, totalW, totalH);
            } else {
                p.window.setWidth(totalW);
                p.window.setHeight(totalH);
                p.window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
            }
        } catch (Throwable error) {
            Log.e(TAG, "Unable to show first-row key popup", error);
        }
    }

    /** id = touch identifier, or -1 to hide all immediately. */
    public void hide(int id) {
        try {
            if (id < 0) {
                for (int i = 0; i < popups.size(); i++) {
                    Popup p = popups.valueAt(i);
                    cancelPendingHide(p);
                    dismiss(p);
                }
                return;
            }

            Popup p = popups.get(id);
            if (p == null) return;

            // HTML removePopup() leaves its preview visible for 80 ms after touchend.
            // Match that exact release tail for the native first-row preview.
            cancelPendingHide(p);
            p.pendingHide = () -> {
                p.pendingHide = null;
                dismiss(p);
            };
            handler.postDelayed(p.pendingHide, HIDE_DELAY_MS);
        } catch (Throwable error) {
            Log.e(TAG, "Unable to hide key popup", error);
        }
    }

    public void release() {
        hide(-1);
        popups.clear();
    }

    private void cancelPendingHide(Popup p) {
        if (p.pendingHide != null) {
            handler.removeCallbacks(p.pendingHide);
            p.pendingHide = null;
        }
    }

    private static void dismiss(Popup p) {
        if (p.window.isShowing()) p.window.dismiss();
    }

    private static final class Popup {
        final PopupView view;
        final PopupWindow window;
        Runnable pendingHide;

        Popup(Context c) {
            view = new PopupView(c);
            window = new PopupWindow(view, 1, 1, false);
            window.setClippingEnabled(false);
            window.setTouchable(false);
            window.setFocusable(false);
            window.setBackgroundDrawable(null);
            window.setAnimationStyle(0);
            window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
            if (Build.VERSION.SDK_INT >= 29) {
                window.setIsLaidOutInScreen(true);
                window.setIsClippedToScreen(false);
            }
        }
    }

    /** Pixel-for-pixel port of the HTML popup bubble and stem. */
    private static final class PopupView extends View {
        final int pad;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private String text = "";
        private float w, h, sx, sw, sh, unit;

        PopupView(Context c) {
            super(c);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            float d = c.getResources().getDisplayMetrics().density;
            pad = Math.round(6 * d);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.parseColor("#FAFAFC"));
            txt.setColor(Color.parseColor("#111111"));
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }

        void set(String text, float w, float h, float sx, float sw, float sh, float unit) {
            this.text = text;
            this.w = w;
            this.h = h;
            this.sx = sx;
            this.sw = sw;
            this.sh = sh;
            this.unit = unit;
            fill.setShadowLayer(2f * unit, 0f, 1f * unit, 0x59000000);
            txt.setTextSize(34f * unit);
            invalidate();
        }

        int totalW() {
            return Math.round(w) + pad * 2;
        }

        int totalH() {
            return Math.round(Math.max(h, h - 6f * unit + sh)) + pad * 2;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = 10f * unit;
            float sr = 5.5f * unit;
            Path bubble = new Path();
            bubble.addRoundRect(
                    new RectF(pad, pad, pad + w, pad + h),
                    r, r, Path.Direction.CW);
            shape.reset();
            shape.addPath(bubble);

            if (sh > 0) {
                float top = pad + h - 6f * unit;
                Path stem = new Path();
                stem.addRoundRect(
                        new RectF(pad + sx, top, pad + sx + sw, top + sh),
                        new float[]{0, 0, 0, 0, sr, sr, sr, sr},
                        Path.Direction.CW);
                shape.op(stem, Path.Op.UNION);
            }

            canvas.drawPath(shape, fill);
            Paint.FontMetrics fm = txt.getFontMetrics();
            float cy = pad + h / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, pad + w / 2f, cy, txt);
        }
    }
}
