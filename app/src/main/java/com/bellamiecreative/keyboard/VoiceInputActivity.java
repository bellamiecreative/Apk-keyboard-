package com.bellamiecreative.keyboard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public class VoiceInputActivity extends Activity {
    private static final int REQUEST_SPEECH = 1001;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent speech = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speech.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to type");
        boolean kurdish = getIntent().getBooleanExtra("kurdish", false);
        speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE, kurdish ? "ckb-IQ" : Locale.getDefault().toLanguageTag());
        speech.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        try {
            startActivityForResult(speech, REQUEST_SPEECH);
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(this, "No speech recognition app is available on this device.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SPEECH && resultCode == RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) {
                Intent result = new Intent("com.bellamiecreative.keyboard.VOICE_RESULT");
                result.setPackage(getPackageName());
                result.putExtra("recognized_text", matches.get(0));
                sendBroadcast(result);
            }
        }
        finish();
    }
}
