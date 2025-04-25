package com.je.emojikitchen;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.MediaScannerConnection;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.net.Uri;

import androidx.appcompat.app.*;
import androidx.core.app.ActivityCompat;
import androidx.core.content.*;
import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "EmojiKitchenPrefs", API = "https://emojik.vercel.app/s/%s_%s?size=%d";
    private static final int PERMISSION_CODE = 123, DEFAULT_SIZE = 128, VIBRATION_COOLDOWN = 100;
    private GridView grid1, grid2;
    private ImageView resultImage;
    private SeekBar slider;
    private Button shareBtn, saveBtn;
    private View loading;
    private EmojiAdapter adapter1, adapter2;
    private String emoji1, emoji2;
    private String[] emojis;
    private EmojiCache cache;
    private int currentRequestSize = 0;
    private long lastVibrate = 0;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        getWindow().getDecorView().setBackgroundColor(ContextCompat.getColor(this, R.color.background_dark));
        
        emojis = getResources().getStringArray(R.array.emoji_codes);
        grid1 = findViewById(R.id.emojiGrid1);
        grid2 = findViewById(R.id.emojiGrid2);
        resultImage = findViewById(R.id.resultImage);
        slider = findViewById(R.id.sizeSlider);
        shareBtn = findViewById(R.id.shareButton);
        saveBtn = findViewById(R.id.saveButton);
        loading = findViewById(R.id.loadingIndicator);
        cache = EmojiCache.getInstance(this);

        adapter1 = new EmojiAdapter(this, emojis);
        adapter2 = new EmojiAdapter(this, emojis);
        grid1.setAdapter(adapter1);
        grid2.setAdapter(adapter2);

        grid1.setOnItemClickListener((p, v, i, id) -> onEmojiSelected(i, true));
        grid2.setOnItemClickListener((p, v, i, id) -> onEmojiSelected(i, false));

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int last = -1;
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && Math.abs(p - last) > 10) {
                    last = p;
                    updateResult();
                }
            }
            public void onStartTrackingTouch(SeekBar sb) { last = sb.getProgress(); }
            public void onStopTrackingTouch(SeekBar sb) {
                updateResult(); saveState();
            }
        });

        shareBtn.setOnClickListener(v -> shareImage());
        saveBtn.setOnClickListener(v -> checkPermissionsAndSave());

        restoreState();
    }

    private void onEmojiSelected(int pos, boolean first) {
        (first ? grid1 : grid2).performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (first) { emoji1 = emojis[pos]; adapter1.setSelectedPosition(pos); }
        else { emoji2 = emojis[pos]; adapter2.setSelectedPosition(pos); }
        saveState();
        currentRequestSize = 0;
        resultImage.setImageDrawable(null);
        updateResult();
    }

    private void updateResult() {
        if (emoji1 == null || emoji2 == null) return;
        int reqSize = Math.max(16, Math.min(512, (slider.getProgress() / 16) * 16));
        if (reqSize <= currentRequestSize && resultImage.getDrawable() != null) {
            resizeImage(reqSize);
            return;
        }
        currentRequestSize = reqSize;
        resizeImage(reqSize);
        if (loadFromCache(reqSize)) return;
        loadFromNetwork(reqSize);
    }

    private void resizeImage(int size) {
        ViewGroup.LayoutParams params = resultImage.getLayoutParams();
        params.width = size; params.height = size;
        resultImage.setLayoutParams(params);
    }

    private boolean loadFromCache(int size) {
        Integer cached = cache.getCachedSize(emoji1, emoji2);
        if (cached != null && cached >= size) {
            Bitmap bmp = cache.loadFromCache(emoji1, emoji2, size);
            if (bmp != null) {
                setImage(bmp); vibrate(HapticFeedbackConstants.LONG_PRESS);
                return true;
            }
        }
        return false;
    }

    private void loadFromNetwork(int size) {
        String url = String.format(API, emoji1, emoji2, size);
        loading.setVisibility(View.VISIBLE); resultImage.setVisibility(View.INVISIBLE);
        Glide.with(this).asBitmap().load(url).into(new CustomTarget<Bitmap>() {
            public void onResourceReady(Bitmap bmp, Transition<? super Bitmap> t) {
                if (bmp != null) {
                    cache.saveToCache(emoji1, emoji2, bmp);
                    setImage(bmp); hideLoading(); doubleVibrate();
                }
            }
            public void onLoadCleared(Drawable placeholder) { hideLoading(); }
            public void onLoadFailed(Drawable e) {
                hideLoading(); Toast.makeText(MainActivity.this, "Failed to load image", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setImage(Bitmap bmp) {
        resultImage.setImageBitmap(bmp);
        shareBtn.setEnabled(true); saveBtn.setEnabled(true);
    }

    private void hideLoading() {
        loading.setVisibility(View.GONE); resultImage.setVisibility(View.VISIBLE);
    }

    private void doubleVibrate() {
        vibrate(HapticFeedbackConstants.VIRTUAL_KEY);
        new Handler(Looper.getMainLooper()).postDelayed(() -> vibrate(HapticFeedbackConstants.VIRTUAL_KEY), 100);
    }

    private void vibrate(int type) {
        long now = System.currentTimeMillis();
        if (now - lastVibrate > VIBRATION_COOLDOWN) {
            resultImage.performHapticFeedback(type); lastVibrate = now;
        }
    }

    private void checkPermissionsAndSave() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_CODE);
        } else saveToGallery();
    }

    private void saveToGallery() {
        Bitmap bmp = ((BitmapDrawable) resultImage.getDrawable()).getBitmap();
        if (bmp == null || emoji1 == null || emoji2 == null) return;

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "EmojiKitchen");
        dir.mkdirs();
        File file = new File(dir, String.format("emoji_%s_%s.png", emoji1, emoji2));

        try (FileOutputStream out = new FileOutputStream(file)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            MediaScannerConnection.scanFile(this, new String[]{file.toString()}, null, (p, uri) -> runOnUiThread(() ->
                Toast.makeText(this, "Saved to gallery", Toast.LENGTH_SHORT).show()));
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareImage() {
        Bitmap bmp = ((BitmapDrawable) resultImage.getDrawable()).getBitmap();
        if (bmp == null || emoji1 == null || emoji2 == null) return;

        try {
            File file = new File(getCacheDir(), "images/emoji_" + emoji1 + "_" + emoji2 + ".png");
            file.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }

            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share emoji"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveState() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (emoji1 != null) e.putString("selectedEmoji1", emoji1);
        if (emoji2 != null) e.putString("selectedEmoji2", emoji2);
        e.putInt("sliderSize", slider.getProgress());
        e.putInt("scrollPos1", grid1.getFirstVisiblePosition());
        e.putInt("scrollPos2", grid2.getFirstVisiblePosition());
        e.apply();
    }

    private void restoreState() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        emoji1 = p.getString("selectedEmoji1", null);
        emoji2 = p.getString("selectedEmoji2", null);
        slider.setProgress(p.getInt("sliderSize", DEFAULT_SIZE));
        int i1 = emoji1 == null ? -1 : Arrays.asList(emojis).indexOf(emoji1);
        int i2 = emoji2 == null ? -1 : Arrays.asList(emojis).indexOf(emoji2);
        if (i1 >= 0) { adapter1.setSelectedPosition(i1); grid1.setSelection(p.getInt("scrollPos1", 0)); }
        if (i2 >= 0) { adapter2.setSelectedPosition(i2); grid2.setSelection(p.getInt("scrollPos2", 0)); }
        if (emoji1 != null && emoji2 != null) updateResult();
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == PERMISSION_CODE && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) saveToGallery();
        else Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
    }
}