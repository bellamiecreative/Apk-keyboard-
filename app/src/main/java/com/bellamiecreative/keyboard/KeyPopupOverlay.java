package com.bellamiecreative.keyboard;

import android.content.Context;
import android.os.Build;
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
 * Draws the key-press bubble for keys whose HTML popup would leave the top of the WebView
 * (first row). Geometry, colors, radii and shadow mirror .keyPop / .keyPopBubble / .keyPopStem
 * in the HTML. It is a child window of the keyboard's own WebView (no permission needed) and
 * clipping is disabled, so it can extend above the IME window. It never changes the IME or
 * WebView size.
 *
 * All methods must be called on the UI thread.
 */
public class KeyPopupOverlay {

    private static final String TAG = "KeyPopupOverlay";
    private final View anchor;                 // the keyboard WebView
    private final SparseArray<Popup> popups = new SparseArray<>();

    public KeyPopupOverlay(View anchorWebView) {
        this.anchor = anchorWebView;
    }

    /** json: {id,text,l,t,w,h,sx,sw,sh,s,dpr} - values in CSS px relative to the WebView's top-left. */
    public void show(String json) {
        try {
            if (anchor == null || anchor.getWindowToken() == null) return;
            JSONObject o = new JSONObject(json);
            int id = o.optInt("id");
            String text = o.optString("text", "");
            if (text.length() == 0) { hide(id); return; }

            float s = (float) o.optDouble("s", 1);       // 390-layout px -> CSS px (viewport) scale
            // CSS px -> device px: exact ratio of the native view width to the page's viewport width
            float vw = (float) o.optDouble("vw", 0);
            float px = (vw > 0 && anchor.getWidth() > 0)
                    ? anchor.getWidth() / vw
                    : (float) o.optDouble("dpr", anchor.getResources().getDisplayMetrics().density);

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
            p.view.set(text, w, h, sx, sw, sh, s * px);

            // Match the coordinate space used by PopupWindow. On API 29+ the
            // popup is explicitly laid out in screen coordinates; older Android
            // versions use coordinates relative to the IME window.
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
            // Keep typing safe, but make native popup failures diagnosable in Logcat.
            Log.e(TAG, "Unable to show first-row key popup", error);
        }
    }

    /** id = touch identifier, or -1 to hide all. */
    public void hide(int id) {
        try {
            if (id < 0) {
                for (int i = 0; i < popups.size(); i++) dismiss(popups.valueAt(i));
                return;
            }
            Popup p = popups.get(id);
            if (p != null) dismiss(p);
        } catch (Throwable error) {
            Log.e(TAG, "Unable to hide key popup", error);
        }
    }

    public void release() {
        hide(-1);
        popups.clear();
    }

    private static void dismiss(Popup p) {
        if (p.window.isShowing()) p.window.dismiss();
    }

    // ------------------------------------------------------------------

    private static final class Popup {
        final PopupView view;
        final PopupWindow window;

        Popup(Context c) {
            view = new PopupView(c);
            window = new PopupWindow(view, 1, 1, false);
            window.setClippingEnabled(false);      // allow drawing above the IME window / screen edge
            window.setTouchable(false);            // touches stay with the keyboard WebView
            window.setFocusable(false);
            window.setBackgroundDrawable(null);
            window.setAnimationStyle(0);
            window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
            // Critical: getLocationOnScreen() coordinates must be interpreted as
            // absolute screen coordinates, not relative to the IME window frame.
            if (Build.VERSION.SDK_INT >= 29) {
                window.setIsLaidOutInScreen(true);
                window.setIsClippedToScreen(false);
            }
        }
    }

    /** Pixel-for-pixel port of .keyPop (bubble #FAFAFC r=10, stem r=5.5, drop-shadow 0 1 2 rgba(0,0,0,.35)). */
    private static final class PopupView extends View {
        final int pad;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private String text = "";
        private float w, h, sx, sw, sh, unit;      // unit = device px per 390-layout px

        PopupView(Context c) {
            super(c);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            float d = c.getResources().getDisplayMetrics().density;
            pad = Math.round(6 * d);               // room for the shadow
            setLayerType(LAYER_TYPE_SOFTWARE, null); // required for shadow layer on paths
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.parseColor("#FAFAFC"));
            txt.setColor(Color.parseColor("#111111"));
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }

        void set(String text, float w, float h, float sx, float sw, float sh, float unit) {
            this.text = text;
            this.w = w; this.h = h; this.sx = sx; this.sw = sw; this.sh = sh; this.unit = unit;
            // shadow: blur 2 css px, offset y 1 css px, 35% black (layout units -> device px)
            fill.setShadowLayer(2f * unit, 0f, 1f * unit, 0x59000000);
            txt.setTextSize(34f * unit);
            invalidate();
        }

        int totalW() { return Math.round(w) + pad * 2; }

        int totalH() { return Math.round(Math.max(h, h - 6f * unit + sh)) + pad * 2; }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = 10f * unit, sr = 5.5f * unit;
            Path bubble = new Path();
            bubble.addRoundRect(new RectF(pad, pad, pad + w, pad + h), r, r, Path.Direction.CW);
            shape.reset();
            shape.addPath(bubble);
            if (sh > 0) {
                float top = pad + h - 6f * unit;
                Path stem = new Path();
                stem.addRoundRect(new RectF(pad + sx, top, pad + sx + sw, top + sh),
                        new float[]{0, 0, 0, 0, sr, sr, sr, sr}, Path.Direction.CW);
                shape.op(stem, Path.Op.UNION);
                // the HTML stem is a rectangle whose TOP is flush inside the bubble, so no gap appears
            }
            canvas.drawPath(shape, fill);

            Paint.FontMetrics fm = txt.getFontMetrics();
            float cy = pad + h / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, pad + w / 2f, cy, txt);
        }
    }
}
