package com.je.emojikitchen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class EmojiCache {

    private static final int MAX_CACHE_SIZE = 100 * 1024 * 1024; // 100MB
    private static EmojiCache instance;
    private final File cacheDir;
    private final Map<String, Integer> sizeCache;

    private EmojiCache(Context context) {
        this.cacheDir = new File(context.getCacheDir(), "emoji_cache");
        this.sizeCache = new HashMap<>();
        initializeCache();
    }

    public static synchronized EmojiCache getInstance(Context context) {
        if (instance == null) {
            instance = new EmojiCache(context);
        }
        return instance;
    }

    private void initializeCache() {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    public synchronized void saveToCache(String emoji1, String emoji2, Bitmap bitmap) {
        String key = getCacheKey(emoji1, emoji2);
        if (bitmap == null || bitmap.getWidth() < 16 || bitmap.getHeight() < 16) {
            return;
        }

        try {
            File file = new File(cacheDir, key + ".png");
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                sizeCache.put(key, bitmap.getWidth());
                trimCache();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized Integer getCachedSize(String emoji1, String emoji2) {
        return sizeCache.get(getCacheKey(emoji1, emoji2));
    }

    public synchronized Bitmap loadFromCache(String emoji1, String emoji2, int reqWidth) {
        String key = getCacheKey(emoji1, emoji2);
        File file = new File(cacheDir, key + ".png");
        if (file.exists()) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);

            int imageWidth = options.outWidth;
            int imageHeight = options.outHeight;

            int inSampleSize = 1;
            if (imageHeight > reqWidth || imageWidth > reqWidth) {
                final int halfHeight = imageHeight / 2;
                final int halfWidth = imageWidth / 2;

                while ((halfHeight / inSampleSize) >= reqWidth && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2;
                }
            }

            options.inSampleSize = inSampleSize;
            options.inJustDecodeBounds = false;

            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        }
        return null;
    }

    private String getCacheKey(String emoji1, String emoji2) {
        return String.format("emoji_%s_%s", emoji1, emoji2);
    }

    public synchronized void clearCache() {
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        sizeCache.clear();
    }

    private void trimCache() {
        int cacheSize = 0;
        for (int size : sizeCache.values()) {
            cacheSize += size;
        }

        if (cacheSize > MAX_CACHE_SIZE) {
            for (Map.Entry<String, Integer> entry : sizeCache.entrySet()) {
                File file = new File(cacheDir, entry.getKey() + ".png");
                if (file.exists()) {
                    file.delete();
                }
            }
            sizeCache.clear();
        }
    }
}