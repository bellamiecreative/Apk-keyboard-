package com.bellamiecreative.keyboard;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts / decrypts the password file with AES-256-GCM.
 *
 * The AES key is generated inside the Android Keystore and never leaves it: this app only
 * ever holds a handle to the key, so the raw key bytes cannot be read from app storage, from
 * a copy of the app's files, or from a backup. No PIN, biometric or user-authentication
 * requirement is attached to the key, so passwords are available immediately.
 *
 * Stored layout:  MAGIC(4) | ivLength(1) | iv | ciphertext+GCM tag
 */
final class SecureCodec implements RecordStore.Codec {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "bellamie_password_store_key_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] MAGIC = {'B', 'K', 'P', '1'};
    // Authenticated (not encrypted) context: ties the ciphertext to this file type/version.
    private static final byte[] AAD = "bellamie-password-store-v1".getBytes(StandardCharsets.UTF_8);

    private SecretKey loadKey(boolean createIfMissing) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        if (!createIfMissing) throw new GeneralSecurityException("key_missing");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }

    @Override public byte[] encode(byte[] plain) throws Exception {
        SecretKey key = loadKey(true);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        // The Keystore picks a fresh random IV for every encryption.
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(AAD);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain);
        byte[] out = new byte[MAGIC.length + 1 + iv.length + encrypted.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        out[MAGIC.length] = (byte) iv.length;
        System.arraycopy(iv, 0, out, MAGIC.length + 1, iv.length);
        System.arraycopy(encrypted, 0, out, MAGIC.length + 1 + iv.length, encrypted.length);
        return out;
    }

    @Override public byte[] decode(byte[] stored) throws Exception {
        if (stored == null || stored.length < MAGIC.length + 2) {
            throw new GeneralSecurityException("bad_format");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (stored[i] != MAGIC[i]) throw new GeneralSecurityException("bad_format");
        }
        int ivLength = stored[MAGIC.length] & 0xFF;
        int ivStart = MAGIC.length + 1;
        int dataStart = ivStart + ivLength;
        if (ivLength < 12 || dataStart >= stored.length) throw new GeneralSecurityException("bad_format");
        byte[] iv = new byte[ivLength];
        System.arraycopy(stored, ivStart, iv, 0, ivLength);
        SecretKey key = loadKey(false);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(AAD);
        return cipher.doFinal(stored, dataStart, stored.length - dataStart);
    }

    /** Removes the Keystore key (used only by the explicit "start fresh" reset). */
    void deleteKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) { }
    }
}
