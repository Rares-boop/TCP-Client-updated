package com.vladurares.tcpclient.storage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class ProfilePictureCache {
    private static final String TAG = "ProfilePicCache";
    private static final String CACHE_FILE = "profile_pics_cache.json";
    private static final Map<Integer, String> ramCache = new HashMap<>();
    private static final Map<Integer, Bitmap> bitmapCache = new HashMap<>();

    public static void loadFromDisk(Context context) {
        try {
            File file = new File(context.getFilesDir(), CACHE_FILE);
            if (!file.exists()) return;

            FileInputStream fis = new FileInputStream(file);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            fis.close();

            String json = baos.toString("UTF-8");
            JSONObject obj = new JSONObject(json);

            ramCache.clear();
            bitmapCache.clear();

            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String base64 = obj.optString(key, "");
                if (!base64.isEmpty()) {
                    int chatId = Integer.parseInt(key);
                    ramCache.put(chatId, base64);
                }
            }

            Log.i(TAG, "Loaded " + ramCache.size() + " cached profile pics from disk.");

        } catch (Exception e) {
            Log.w(TAG, "Failed to load cache from disk", e);
        }
    }

    public static void saveToDisk(Context context) {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<Integer, String> entry : ramCache.entrySet()) {
                obj.put(String.valueOf(entry.getKey()), entry.getValue());
            }

            File file = new File(context.getFilesDir(), CACHE_FILE);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();

            Log.i(TAG, "Saved " + ramCache.size() + " profile pics to disk.");

        } catch (Exception e) {
            Log.w(TAG, "Failed to save cache to disk", e);
        }
    }

    public static void updateFromServer(Context context, Map<Integer, String> serverData) {
        for (Map.Entry<Integer, String> entry : serverData.entrySet()) {
            int chatId = entry.getKey();
            String base64 = entry.getValue();

            if (base64 != null && !base64.isEmpty()) {
                ramCache.put(chatId, base64);
                bitmapCache.remove(chatId); // invalidează bitmap-ul vechi
            }
        }
        saveToDisk(context);
    }

    public static Bitmap getBitmap(int chatId) {
        Bitmap cached = bitmapCache.get(chatId);
        if (cached != null) return cached;

        String base64 = ramCache.get(chatId);
        if (base64 == null || base64.isEmpty()) return null;

        try {
            byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                bitmapCache.put(chatId, bmp);
            }
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "Failed to decode bitmap for chat " + chatId, e);
            return null;
        }
    }

    public static boolean has(int chatId) {
        return ramCache.containsKey(chatId) && !ramCache.get(chatId).isEmpty();
    }

    public static void clear(Context context) {
        ramCache.clear();
        bitmapCache.clear();
        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (file.exists()) file.delete();
    }

    private ProfilePictureCache() {
        throw new UnsupportedOperationException("Utility class");
    }
}

