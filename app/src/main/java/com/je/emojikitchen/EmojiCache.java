package com.je.emojikitchen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import androidx.collection.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class EmojiCache {
    private static final int MAX_MEMORY_CACHE_SIZE = (int) (Runtime.getRuntime().maxMemory() / 8); // Use 1/8th of available memory
    private static final int MAX_DISK_CACHE_SIZE = 100 * 1024 * 1024; // 100MB
    private static EmojiCache instance;
    
    private final File cacheDir;
    private final LruCache<String, Bitmap> memoryCache;
    private final LinkedHashMap<String, Long> diskCache; // Key -> File size mapping
    private long currentDiskCacheSize;

    private EmojiCache(Context context) {
        this.cacheDir = new File(context.getCacheDir(), "emoji_cache");
        this.diskCache = new LinkedHashMap<>(100, 0.75f, true);
        this.currentDiskCacheSize = 0;
        
        this.memoryCache = new LruCache<String, Bitmap>(MAX_MEMORY_CACHE_SIZE) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    return bitmap.getAllocationByteCount();
                }
                return bitmap.getByteCount();
            }
            
            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                if (evicted && oldValue != null && !oldValue.isRecycled()) {
                    oldValue.recycle();
                }
            }
        };
        
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

        Bitmap existing = memoryCache.get(key);
        int bmpSize = Math.max(bitmap.getWidth(), bitmap.getHeight());
        int existingSize = existing != null ? Math.max(existing.getWidth(), existing.getHeight()) : -1;
        if (existing == null || bmpSize > existingSize) {
            memoryCache.put(key, bitmap.copy(bitmap.getConfig(), false));
            // Save to disk cache
            try {
                File file = new File(cacheDir, key + ".png");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    long fileSize = file.length();
                    diskCache.put(key, fileSize);
                    currentDiskCacheSize += fileSize;
                    trimDiskCache();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        // else: do not store smaller images
    }

    public synchronized Bitmap loadFromCache(String emoji1, String emoji2, int reqWidth) {
        String key = getCacheKey(emoji1, emoji2);

        // Try memory cache first
        Bitmap cached = memoryCache.get(key);
        if (cached != null) {
            return cached;
        }

        // Try disk cache
        File file = new File(cacheDir, key + ".png");
        if (file.exists()) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inMutable = true;

            if (reqWidth > 0) {
                // Calculate sample size
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqWidth);
                options.inJustDecodeBounds = false;
            }

            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap != null) {
                memoryCache.put(key, bitmap);
            }
            return bitmap;
        }
        return null;
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private void trimDiskCache() {
        while (currentDiskCacheSize > MAX_DISK_CACHE_SIZE && !diskCache.isEmpty()) {
            Map.Entry<String, Long> eldest = diskCache.entrySet().iterator().next();
            File file = new File(cacheDir, eldest.getKey() + ".png");
            if (file.exists()) {
                currentDiskCacheSize -= eldest.getValue();
                file.delete();
            }
            diskCache.remove(eldest.getKey());
        }
    }

    public synchronized void clearCache() {
        memoryCache.evictAll();
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        diskCache.clear();
        currentDiskCacheSize = 0;
    }

    private String getCacheKey(String emoji1, String emoji2) {
        return String.format("emoji_%s_%s", emoji1, emoji2);
    }
}