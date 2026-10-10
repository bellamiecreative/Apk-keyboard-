package com.bellamiecreative.keyboard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
        note.setText("Enable the keyboard in Android Settings, then choose it as your active on-screen keyboard.");
        note.setTextSize(16);
        note.setTextColor(0xFF333333);
        note.setPadding(0, 18, 0, 24);
        root.addView(note);
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
