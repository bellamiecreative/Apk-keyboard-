package com.bellamiecreative.keyboard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String PREF_VOLUME = "sound_volume";
    private static final int INK = Color.rgb(25, 38, 59);
    private static final int MUTED = Color.rgb(83, 96, 117);
    private static final int PALE = Color.rgb(239, 242, 248);
    private static final int ACCENT = Color.rgb(151, 162, 181);
    private SharedPreferences preferences;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("keyboard_settings", MODE_PRIVATE);

        getWindow().setStatusBarColor(Color.rgb(242, 244, 249));
        getWindow().setNavigationBarColor(Color.rgb(226, 230, 239));
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(Color.rgb(242, 244, 249));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(34));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Brand header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), dp(8), dp(2), dp(24));
        ImageView logo = new ImageView(this);
        logo.setImageResource(com.bellamiecreative.keyboard.R.drawable.ic_keyboard_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logo.setBackground(roundRect(Color.WHITE, dp(30), Color.rgb(210, 216, 228), dp(1)));
        logo.setElevation(dp(7));
        header.addView(logo, new LinearLayout.LayoutParams(dp(112), dp(112)));

        View divider = new View(this);
        divider.setBackgroundColor(ACCENT);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(3), dp(104));
        dividerParams.setMargins(dp(18), 0, dp(18), 0);
        header.addView(divider, dividerParams);

        LinearLayout brandText = new LinearLayout(this);
        brandText.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("Bellamie\nKeyboard", 29, INK, true);
        brand.setLineSpacing(dp(1), 1f);
        brandText.addView(brand);
        TextView tagline = text("Smart  •  Stylish  •  Reliable", 14, MUTED, false);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagParams.topMargin = dp(8);
        brandText.addView(tagline, tagParams);
        header.addView(brandText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        // Intro card
        LinearLayout intro = card();
        intro.setGravity(Gravity.CENTER_VERTICAL);
        intro.setPadding(dp(18), dp(20), dp(18), dp(20));
        intro.addView(iconBadge("⚙", 54), new LinearLayout.LayoutParams(dp(82), dp(82)));
        LinearLayout introText = new LinearLayout(this);
        introText.setOrientation(LinearLayout.VERTICAL);
        TextView introTitle = text("Bellamie Keyboard", 20, INK, true);
        introText.addView(introTitle);
        TextView note = text("Choose your keyboard preferences, then enable Bellamie Keyboard and select it as your active keyboard.", 15, MUTED, false);
        note.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(8);
        introText.addView(note, noteParams);
        LinearLayout.LayoutParams introTextParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        introTextParams.leftMargin = dp(16);
        intro.addView(introText, introTextParams);
        addCard(root, intro, dp(18));

        // Preference switches
        LinearLayout switches = card();
        switches.setOrientation(LinearLayout.VERTICAL);
        switches.setPadding(dp(18), dp(8), dp(18), dp(8));
        Switch vibration = settingSwitch("Key vibration", "Vibrate when typing", "♫",
                preferences.getBoolean("vibration_enabled", true));
        vibration.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean("vibration_enabled", checked).apply());
        addSettingRow(switches, vibration, "Vibrate when typing", "≋", true);

        View line = new View(this);
        line.setBackgroundColor(Color.rgb(226, 230, 238));
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lineParams.setMargins(dp(4), dp(5), dp(4), dp(5));
        switches.addView(line, lineParams);

        Switch sound = settingSwitch("Key sound", "Play sound on key press", "◖",
                preferences.getBoolean("sound_enabled", true));
        sound.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean("sound_enabled", checked).apply());
        addSettingRow(switches, sound, "Play sound on key press", "◖", false);
        addCard(root, switches, dp(18));

        // Sound volume
        LinearLayout volumeCard = card();
        volumeCard.setOrientation(LinearLayout.VERTICAL);
        volumeCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        LinearLayout volumeTop = new LinearLayout(this);
        volumeTop.setGravity(Gravity.CENTER_VERTICAL);
        volumeTop.addView(iconBadge("◖", 42), new LinearLayout.LayoutParams(dp(66), dp(66)));
        TextView volumeLabel = text("", 18, INK, true);
        LinearLayout.LayoutParams volumeLabelParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        volumeLabelParams.leftMargin = dp(16);
        volumeTop.addView(volumeLabel, volumeLabelParams);
        volumeCard.addView(volumeTop);

        SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        int savedVolume = Math.max(0, Math.min(100, preferences.getInt(PREF_VOLUME, 100)));
        volume.setProgress(savedVolume);
        volumeLabel.setText("Sound volume: " + savedVolume + "%");
        volume.setContentDescription("Keyboard sound volume, 0 to 100 percent");
        if (Build.VERSION.SDK_INT >= 21) {
            volume.setProgressTintList(ColorStateList.valueOf(ACCENT));
            volume.setThumbTintList(ColorStateList.valueOf(MUTED));
            volume.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(218, 223, 233)));
        }
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                volumeLabel.setText("Sound volume: " + progress + "%");
                if (fromUser) preferences.edit().putInt(PREF_VOLUME, progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        seekParams.leftMargin = dp(78);
        volumeCard.addView(volume, seekParams);
        addCard(root, volumeCard, dp(24));

        // System keyboard actions
        Button enable = actionButton("▦     ENABLE KEYBOARD", true);
        enable.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(enable, buttonParams());

        Button choose = actionButton("▦     CHOOSE KEYBOARD", false);
        choose.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        LinearLayout.LayoutParams chooseParams = buttonParams();
        chooseParams.topMargin = dp(14);
        root.addView(choose, chooseParams);

        setContentView(scroll);
    }

    private Switch settingSwitch(String title, String description, String icon, boolean checked) {
        Switch control = new Switch(this);
        control.setText(title);
        control.setTextSize(17);
        control.setTextColor(INK);
        control.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        control.setChecked(checked);
        control.setPadding(0, dp(7), 0, dp(7));
        if (Build.VERSION.SDK_INT >= 21) {
            control.setThumbTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{MUTED, Color.rgb(205, 211, 222)}));
            control.setTrackTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{Color.rgb(183, 192, 208), Color.rgb(225, 229, 237)}));
        }
        return control;
    }

    private void addSettingRow(LinearLayout parent, Switch control, String description,
                               String icon, boolean first) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(iconBadge(icon, 42), new LinearLayout.LayoutParams(dp(66), dp(66)));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(control);
        TextView sub = text(description, 14, MUTED, false);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.leftMargin = dp(2);
        content.addView(sub, subParams);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        contentParams.leftMargin = dp(14);
        row.addView(content, contentParams);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(102)));
    }

    private Button actionButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setBackground(roundRect(primary
                ? new int[]{Color.rgb(151, 163, 184), Color.rgb(111, 124, 146)}
                : new int[]{Color.rgb(249, 250, 253), Color.rgb(218, 224, 235)},
                dp(48), Color.rgb(207, 214, 226), dp(1)));
        button.setElevation(dp(6));
        return button;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setBackground(roundRect(Color.rgb(250, 251, 254), dp(30),
                Color.rgb(255, 255, 255), dp(1)));
        if (Build.VERSION.SDK_INT >= 21) layout.setElevation(dp(5));
        return layout;
    }

    private void addCard(LinearLayout root, View card, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = bottom;
        root.addView(card, params);
    }

    private View iconBadge(String symbol, int size) {
        TextView icon = text(symbol, size >= 50 ? 38 : 31, INK, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundRect(new int[]{Color.rgb(224, 229, 239),
                Color.rgb(197, 205, 220)}, dp(100), Color.TRANSPARENT, 0));
        return icon;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable roundRect(int color, int radius, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, stroke);
        return drawable;
    }

    private GradientDrawable roundRect(int[] colors, int radius, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        return params;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
