package com.bellamiecreative.keyboard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String PREF_VOLUME = "sound_volume";
    private SharedPreferences preferences;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("keyboard_settings", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 36, 28, 28);
        root.setBackgroundColor(0xFFFFFFFF);

        TextView title = new TextView(this);
        title.setText("Bellamie Keyboard");
        title.setTextSize(26);
        title.setTextColor(0xFF111111);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("Choose your keyboard preferences, then enable Bellamie Keyboard and select it as your active keyboard.");
        note.setTextSize(16);
        note.setTextColor(0xFF333333);
        note.setPadding(0, 18, 0, 18);
        root.addView(note);

        Switch vibration = new Switch(this);
        vibration.setText("Key vibration");
        vibration.setTextSize(16);
        vibration.setPadding(0, 10, 0, 10);
        vibration.setChecked(preferences.getBoolean("vibration_enabled", true));
        vibration.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean("vibration_enabled", checked).apply());
        root.addView(vibration);

        Switch sound = new Switch(this);
        sound.setText("Key sound");
        sound.setTextSize(16);
        sound.setPadding(0, 10, 0, 8);
        sound.setChecked(preferences.getBoolean("sound_enabled", true));
        sound.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean("sound_enabled", checked).apply());
        root.addView(sound);

        TextView volumeLabel = new TextView(this);
        volumeLabel.setTextSize(15);
        volumeLabel.setTextColor(0xFF333333);
        volumeLabel.setPadding(0, 10, 0, 2);
        root.addView(volumeLabel);

        SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        int savedVolume = Math.max(0, Math.min(100, preferences.getInt(PREF_VOLUME, 100)));
        volume.setProgress(savedVolume);
        volumeLabel.setText("Sound volume: " + savedVolume + "%");
        volume.setContentDescription("Keyboard sound volume, 0 to 100 percent");
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                volumeLabel.setText("Sound volume: " + progress + "%");
                if (fromUser) {
                    preferences.edit().putInt(PREF_VOLUME, progress).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        root.addView(volume);

        Button enable = new Button(this);
        enable.setText("Enable keyboard");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(enable);

        Button choose = new Button(this);
        choose.setText("Choose keyboard");
        choose.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        root.addView(choose);

        setContentView(root);
    }
}
