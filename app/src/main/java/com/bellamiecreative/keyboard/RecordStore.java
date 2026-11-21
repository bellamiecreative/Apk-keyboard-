package com.bellamiecreative.keyboard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A small, ordered, file-backed list of records (used for both the Password and the Text
 * managers). The whole list is serialised as one JSON array and passed through a {@link Codec}
 * (identity for text, AES-GCM for passwords) before it touches disk.
 *
 * Every mutation is written to disk immediately (write temp file, fsync, atomic rename), so
 * there is no separate "save" step that could be lost if the keyboard process is killed.
 *
 * The first entry of {@code fields} is the record's ID label and is required.
 * All methods are synchronized; they are called from the WebView's JavaBridge thread.
 */
final class RecordStore {
    interface Codec {
        byte[] encode(byte[] plain) throws Exception;
        byte[] decode(byte[] stored) throws Exception;
    }

    /** Pass-through codec for records that are not secret (Text Manager). */
    static final Codec PLAIN = new Codec() {
        @Override public byte[] encode(byte[] plain) { return plain; }
        @Override public byte[] decode(byte[] stored) { return stored; }
    };

    static final class StoreException extends Exception {
        final String code;
        StoreException(String code) { super(code); this.code = code; }
        StoreException(String code, Throwable cause) { super(code, cause); this.code = code; }
    }

    private final File file;
    private final Codec codec;
    private final String[] fields;
    private final int[] maxLengths;
    private List<JSONObject> cache; // null until the file has been read successfully

    RecordStore(File file, Codec codec, String[] fields, int[] maxLengths) {
        this.file = file;
        this.codec = codec;
        this.fields = fields;
        this.maxLengths = maxLengths;
    }

    synchronized List<JSONObject> all() throws StoreException {
        load();
        return new ArrayList<>(cache);
    }

    synchronized JSONObject get(String uid) throws StoreException {
        load();
        for (JSONObject record : cache) {
            if (record.optString("uid").equals(uid)) return record;
        }
        return null;
    }

    /** Adds a new record (empty / unknown uid) or replaces the record with the same uid. */
    synchronized JSONObject upsert(JSONObject input) throws StoreException {
        load();
        JSONObject record = sanitize(input, input.optString("uid", ""));
        if (record == null) throw new StoreException("invalid");
        if (record.optString(fields[0]).isEmpty()) throw new StoreException("id_required");
        String uid = record.optString("uid");
        List<JSONObject> next = new ArrayList<>(cache);
        int index = -1;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).optString("uid").equals(uid)) { index = i; break; }
        }
        if (index >= 0) next.set(index, record); else next.add(record);
        persist(next);
        cache = next;
        return record;
    }

    synchronized void delete(String uid) throws StoreException {
        load();
        List<JSONObject> next = new ArrayList<>();
        for (JSONObject record : cache) {
            if (!record.optString("uid").equals(uid)) next.add(record);
        }
        if (next.size() == cache.size()) return;
        persist(next);
        cache = next;
    }

    /** Applies a new order. Unknown uids are ignored; records not mentioned keep their relative order at the end. */
    synchronized void reorder(List<String> order) throws StoreException {
        load();
        Map<String, JSONObject> byUid = new HashMap<>();
        for (JSONObject record : cache) byUid.put(record.optString("uid"), record);
        Set<String> placed = new HashSet<>();
        List<JSONObject> next = new ArrayList<>();
        for (String uid : order) {
            JSONObject record = byUid.get(uid);
            if (record != null && placed.add(uid)) next.add(record);
        }
        for (JSONObject record : cache) {
            if (placed.add(record.optString("uid"))) next.add(record);
        }
        persist(next);
        cache = next;
    }

    /** Erases the file (destructive; only reachable through the explicit reset flow). */
    synchronized void reset() {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(file.getParentFile(), file.getName() + ".tmp").delete();
        cache = new ArrayList<>();
    }

    // ---------------------------------------------------------------- internals

    private void load() throws StoreException {
        if (cache != null) return;
        List<JSONObject> loaded = new ArrayList<>();
        if (file.exists()) {
            byte[] stored;
            try {
                stored = readAll(file);
            } catch (IOException e) {
                throw new StoreException("read_failed", e);
            }
            byte[] plain;
            try {
                plain = codec.decode(stored);
            } catch (Exception e) {
                throw new StoreException("decrypt_failed", e);
            }
            try {
                JSONArray array = new JSONArray(new String(plain, StandardCharsets.UTF_8));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject raw = array.optJSONObject(i);
                    if (raw == null) continue;
                    JSONObject clean = sanitize(raw, raw.optString("uid", ""));
                    if (clean != null) loaded.add(clean);
                }
            } catch (JSONException e) {
                throw new StoreException("corrupt", e);
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        }
        cache = loaded;
    }

    private JSONObject sanitize(JSONObject in, String uid) {
        try {
            JSONObject out = new JSONObject();
            out.put("uid", (uid == null || uid.isEmpty()) ? UUID.randomUUID().toString() : uid);
            for (int i = 0; i < fields.length; i++) {
                String value = in.isNull(fields[i]) ? "" : in.optString(fields[i], "");
                if (value.length() > maxLengths[i]) {
                    int cut = maxLengths[i];
                    if (cut > 0 && Character.isHighSurrogate(value.charAt(cut - 1))) cut--;
                    value = value.substring(0, cut);
                }
                if (i == 0) value = value.trim(); // the ID label is trimmed; passwords/text are kept exactly
                out.put(fields[i], value);
            }
            return out;
        } catch (JSONException e) {
            return null;
        }
    }

    private void persist(List<JSONObject> list) throws StoreException {
        JSONArray array = new JSONArray();
        for (JSONObject record : list) array.put(record);
        byte[] plain = array.toString().getBytes(StandardCharsets.UTF_8);
        byte[] stored;
        try {
            stored = codec.encode(plain);
        } catch (Exception e) {
            Arrays.fill(plain, (byte) 0);
            throw new StoreException("encrypt_failed", e);
        }
        // Wipe the plaintext copy, unless the codec returned that same buffer (plain/Text store).
        if (stored != plain) Arrays.fill(plain, (byte) 0);
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(stored);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new StoreException("write_failed", e);
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new StoreException("write_failed");
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
            return buffer.toByteArray();
        }
    }
}
