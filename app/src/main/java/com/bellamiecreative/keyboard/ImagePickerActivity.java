package com.bellamiecreative.keyboard;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

public class ImagePickerActivity extends Activity {
    private static final int REQUEST_IMAGE = 7401;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(pick, REQUEST_IMAGE);
        } catch (RuntimeException error) {
            finish();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) { }
            Intent result = new Intent("com.bellamiecreative.keyboard.IMAGE_PICKED");
            result.setPackage(getPackageName());
            result.putExtra("image_uri", uri.toString());
            sendBroadcast(result);
        }
        finish();
    }
}
