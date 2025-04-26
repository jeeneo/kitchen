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
import android.util.Log;

import androidx.appcompat.app.*;
import androidx.core.app.ActivityCompat;
import androidx.core.content.*;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import java.io.*;
import java.util.*;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "EmojiKitchenPrefs";
    private static final String API = "https://emojik.vercel.app/s/%s_%s?size=%d";
    private static final int PERMISSION_CODE = 123;
    private static final int DEFAULT_SIZE = 128;
    private static final int MIN_SIZE = 16;
    private static final int MAX_SIZE = 512;
    private static final int SIZE_STEP = 16;
    private static final int GRID_COLUMNS = 3;
    private static final int UPDATE_DELAY = 200;
    private static final int VIBRATE_DELAY = 150;
    private static final int POPUP_OFFSET_Y = 60;
    private static final String IMAGE_MIME_TYPE = "image/png";

    private RecyclerView grid1, grid2;
    private EmojiRecyclerAdapter adapter1, adapter2;
    private ImageView resultImage;
    private SeekBar slider;
    private Button shareBtn, saveBtn, copyUrlBtn;
    private View loading;
    private String emoji1, emoji2;
    private String[] emojis;
    private EmojiCache cache;
    private int currentRequestSize = 0;
    private long lastVibrate = 0;
    private Handler updateHandler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;
    private int pendingRequestSize = 0;
    private int currentRequestId = 0;
    private Runnable pendingVibration;
    private Handler vibrateHandler = new Handler(Looper.getMainLooper());
    private PopupWindow sizePopup;
    private TextView sizePopupText;

    @Override 
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        
        if (saved != null) {
            // Don't restore state on configuration change
            emoji1 = saved.getString("emoji1");
            emoji2 = saved.getString("emoji2");
        }
        
        emojis = getResources().getStringArray(R.array.emoji_codes);
        grid1 = findViewById(R.id.emojiGrid1);
        grid2 = findViewById(R.id.emojiGrid2);
        resultImage = findViewById(R.id.resultImage);
        resultImage.setBackgroundResource(R.drawable.emoji_grid_background);
        slider = findViewById(R.id.sizeSlider);
        shareBtn = findViewById(R.id.shareButton);
        saveBtn = findViewById(R.id.saveButton);
        copyUrlBtn = findViewById(R.id.copyUrlButton);
        loading = findViewById(R.id.loadingIndicator);
        cache = EmojiCache.getInstance(this);

        initializeGrids();
        initializeSizePopup();
        initializeSlider();
        initializeButtons();
        
        restoreState();
    }

    private void initializeGrids() {
        GridLayoutManager layoutManager1 = new GridLayoutManager(this, GRID_COLUMNS);
        GridLayoutManager layoutManager2 = new GridLayoutManager(this, GRID_COLUMNS);
        grid1.setLayoutManager(layoutManager1);
        grid2.setLayoutManager(layoutManager2);

        adapter1 = new EmojiRecyclerAdapter(this, emojis, position -> onEmojiSelected(position, true));
        adapter2 = new EmojiRecyclerAdapter(this, emojis, position -> onEmojiSelected(position, false));
        
        grid1.setAdapter(adapter1);
        grid2.setAdapter(adapter2);
    }

    private void initializeSizePopup() {
        sizePopupText = new TextView(this);
        sizePopupText.setTextColor(Color.WHITE);
        sizePopupText.setPadding(16, 8, 16, 8);
        sizePopupText.setBackgroundResource(android.R.drawable.toast_frame);
        sizePopup = new PopupWindow(
            sizePopupText,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int normalizeSize(int value) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, (value / SIZE_STEP) * SIZE_STEP));
    }

    private void updateResult() {
        if (emoji1 == null || emoji2 == null) return;
        int reqSize = pendingRequestSize > 0 ? pendingRequestSize : 
                     normalizeSize(slider.getProgress());
        pendingRequestSize = 0;

        // Try loading from cache first
        Bitmap cached = cache.loadFromCache(emoji1, emoji2, -1); // -1 means get original (largest) cached
        if (cached != null) {
            int cachedSize = Math.max(cached.getWidth(), cached.getHeight());
            if (reqSize <= cachedSize) {
                // Resize in RAM if needed, do not store to disk
                if (reqSize == cachedSize) {
                    setImage(cached);
                } else {
                    Bitmap resized = Bitmap.createScaledBitmap(cached, reqSize, reqSize, true);
                    setImage(resized);
                }
                vibrate(HapticFeedbackConstants.CONTEXT_CLICK);
                return;
            }
            // else: requested size is larger than cached, must fetch from server
        }

        // If not in cache or requested size is larger, load from network
        currentRequestSize = reqSize;
        loadFromNetwork(reqSize);
    }

    private void loadFromNetwork(int size) {
        // Always fetch from server for requested size
        cancelPendingVibrations();
        final int requestId = ++currentRequestId;
        String url = String.format(Locale.US, API, emoji1, emoji2, size);
        showLoading();

        new okhttp3.OkHttpClient().newCall(new okhttp3.Request.Builder().url(url).build())
            .enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, IOException e) {
                    handleNetworkError(requestId, e.getMessage());
                }

                @Override
                public void onResponse(okhttp3.Call call, okhttp3.Response response) {
                    handleNetworkResponse(requestId, response);
                }
            });
    }

    private void handleNetworkError(final int requestId, String error) {
        runOnUiThread(() -> {
            if (requestId == currentRequestId) {
                hideLoading();
                Toast.makeText(this, "Network error: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handleNetworkResponse(final int requestId, okhttp3.Response response) {
        runOnUiThread(() -> {
            if (requestId != currentRequestId) return;
            
            if (response.code() == 404 || "404".equals(response.header("status"))) {
                handleEmojiNotFound();
            } else if (response.isSuccessful()) {
                loadImageWithGlide(requestId, response.request().url().toString());
            } else {
                handleNetworkError(requestId, "HTTP " + response.code());
            }
        });
    }

    private void handleEmojiNotFound() {
        hideLoading();
        Toast.makeText(this, "This emoji combination doesn't exist", Toast.LENGTH_SHORT).show();
        updateButtonStates(false);
        doubleVibrate();
    }

    private void updateButtonStates(boolean enabled) {
        shareBtn.setEnabled(enabled);
        saveBtn.setEnabled(enabled);
        slider.setEnabled(enabled);
        copyUrlBtn.setEnabled(true); // Always enabled
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanupResources();
    }

    private void cleanupResources() {
        if (sizePopup != null && sizePopup.isShowing()) {
            sizePopup.dismiss();
        }
        cancelPendingVibrations();
        updateHandler.removeCallbacksAndMessages(null);
        vibrateHandler.removeCallbacksAndMessages(null);
        
        // Clear any loaded bitmaps
        if (resultImage != null) {
            resultImage.setImageDrawable(null);
        }
    }

    private void onEmojiSelected(int pos, boolean first) {
        cancelPendingVibrations();
        RecyclerView grid = first ? grid1 : grid2;
        grid.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (first) { 
            emoji1 = emojis[pos]; 
            adapter1.setSelectedPosition(pos);
            smoothScrollToCenter(grid1, pos);
        } else { 
            emoji2 = emojis[pos]; 
            adapter2.setSelectedPosition(pos);
            smoothScrollToCenter(grid2, pos);
        }
        saveState();
        currentRequestSize = 0;
        resultImage.setImageDrawable(null);
        updateResult();
    }

    private void smoothScrollToCenter(RecyclerView recyclerView, int position) {
        LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
        int screenWidth = recyclerView.getWidth();
        int itemWidth = screenWidth / GRID_COLUMNS;
        
        // Calculate offset to center the item
        int offset = screenWidth / 2 - itemWidth / 2;
        layoutManager.scrollToPositionWithOffset(position, offset);
    }

    private void resizeImage(int size) {
        ViewGroup.LayoutParams params = resultImage.getLayoutParams();
        params.width = size; params.height = size;
        resultImage.setLayoutParams(params);
    }

    private void loadImageWithGlide(int requestId, String url) {
        Glide.with(this)
            .asBitmap()
            .load(url)
            .into(new CustomTarget<Bitmap>() {
                public void onResourceReady(Bitmap bmp, Transition<? super Bitmap> t) {
                    if (bmp != null && requestId == currentRequestId) {
                        // Only save to cache if this is the largest we've seen for this emoji pair
                        Bitmap cached = cache.loadFromCache(emoji1, emoji2, -1);
                        int bmpSize = Math.max(bmp.getWidth(), bmp.getHeight());
                        int cachedSize = cached != null ? Math.max(cached.getWidth(), cached.getHeight()) : -1;
                        if (cached == null || bmpSize > cachedSize) {
                            cache.saveToCache(emoji1, emoji2, bmp);
                        }
                        setImage(bmp);
                        hideLoading();
                        // Single vibrate for successful network load
                        vibrate(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                }
                public void onLoadCleared(Drawable placeholder) { 
                    hideLoading(); 
                }
                public void onLoadFailed(Drawable e) {
                    if (requestId == currentRequestId) {
                        hideLoading();
                        Toast.makeText(MainActivity.this, "Failed to load image", Toast.LENGTH_SHORT).show();
                    }
                }
            });
    }

    private void setImage(Bitmap bmp) {
        if (bmp != null) {
            resultImage.setImageBitmap(bmp);
            updateButtonStates(true);
            hideLoading();
        }
    }

    private void showLoading() {
        loading.setVisibility(View.VISIBLE); 
        resultImage.setVisibility(View.INVISIBLE);
    }

    private void hideLoading() {
        loading.setVisibility(View.GONE); resultImage.setVisibility(View.VISIBLE);
    }

    private void doubleVibrate() {
        cancelPendingVibrations();
        vibrate(HapticFeedbackConstants.VIRTUAL_KEY);
        pendingVibration = () -> vibrate(HapticFeedbackConstants.VIRTUAL_KEY);
        vibrateHandler.postDelayed(pendingVibration, VIBRATE_DELAY);
    }

    private void cancelPendingVibrations() {
        if (pendingVibration != null) {
            vibrateHandler.removeCallbacks(pendingVibration);
            pendingVibration = null;
        }
        lastVibrate = 0;
    }

    private void checkPermissionsAndSave() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_CODE);
        } else saveToGallery();
    }

    private void shareImage() {
        if (emoji1 == null || emoji2 == null) return;
        
        // Get the cached file directly from EmojiCache
        String key = String.format("emoji_%s_%s", emoji1, emoji2);
        File cacheFile = new File(getCacheDir(), "emoji_cache/" + key + ".png");
        
        if (!cacheFile.exists()) {
            Log.e("MainActivity", "Cache file does not exist: " + cacheFile.getAbsolutePath());
            Toast.makeText(this, "Image not available", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String authority = getPackageName() + ".fileprovider";
            Log.d("MainActivity", "FileProvider authority: " + authority);
            Log.d("MainActivity", "Cache file path: " + cacheFile.getAbsolutePath());
            
            Uri uri = FileProvider.getUriForFile(this, authority, cacheFile);
            if (uri == null) {
                throw new IllegalArgumentException("Failed to create content URI");
            }
            
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(IMAGE_MIME_TYPE);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            // Verify that we have apps that can handle this intent
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(Intent.createChooser(intent, "Share emoji"));
            } else {
                Toast.makeText(this, "No apps available to share image", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Failed to share image", e);
            Toast.makeText(this, "Failed to share image: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveToGallery() {
        if (emoji1 == null || emoji2 == null) return;

        // Get the cached file
        String key = String.format("emoji_%s_%s", emoji1, emoji2);
        File cacheFile = new File(getCacheDir(), "emoji_cache/" + key + ".png");

        if (!cacheFile.exists()) {
            Toast.makeText(this, "Image not available", Toast.LENGTH_SHORT).show();
            return;
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "EmojiKitchen");
        dir.mkdirs();
        File destFile = new File(dir, key + ".png");

        try {
            // Copy the cached file to gallery
            try (FileInputStream in = new FileInputStream(cacheFile);
                 FileOutputStream out = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            
            MediaScannerConnection.scanFile(this, new String[]{destFile.toString()}, null, 
                (p, uri) -> runOnUiThread(() -> Toast.makeText(this, "Saved to gallery", Toast.LENGTH_SHORT).show()));
                
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyUrlToClipboard() {
        if (emoji1 == null || emoji2 == null) return;
        String url = String.format(API, emoji1, emoji2, slider.getProgress());
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Emoji Kitchen URL", url);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "URL copied to clipboard", Toast.LENGTH_SHORT).show();
    }

    private void restoreState() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        emoji1 = p.getString("selectedEmoji1", null);
        emoji2 = p.getString("selectedEmoji2", null);
        slider.setProgress(p.getInt("sliderSize", DEFAULT_SIZE));
        
        int i1 = emoji1 == null ? -1 : Arrays.asList(emojis).indexOf(emoji1);
        int i2 = emoji2 == null ? -1 : Arrays.asList(emojis).indexOf(emoji2);
        
        if (i1 >= 0) {
            adapter1.setSelectedPosition(i1);
            grid1.post(() -> smoothScrollToCenter(grid1, i1));
        }
        if (i2 >= 0) {
            adapter2.setSelectedPosition(i2);
            grid2.post(() -> smoothScrollToCenter(grid2, i2));
        }
        
        if (emoji1 != null && emoji2 != null) {
            shareBtn.setEnabled(true);
            saveBtn.setEnabled(true);
            slider.setEnabled(true);
            copyUrlBtn.setEnabled(true);

            int size = normalizeSize(slider.getProgress());
            Bitmap cached = cache.loadFromCache(emoji1, emoji2, -1);
            if (cached != null) {
                int cachedSize = Math.max(cached.getWidth(), cached.getHeight());
                if (size <= cachedSize) {
                    if (size == cachedSize) {
                        setImage(cached);
                    } else {
                        Bitmap resized = Bitmap.createScaledBitmap(cached, size, size, true);
                        setImage(resized);
                    }
                } else {
                    updateResult();
                }
            } else {
                updateResult();
            }
        }
    }

    private void saveState() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (emoji1 != null) e.putString("selectedEmoji1", emoji1);
        if (emoji2 != null) e.putString("selectedEmoji2", emoji2);
        e.putInt("sliderSize", slider.getProgress());
        e.putInt("scrollPos1", ((LinearLayoutManager)grid1.getLayoutManager()).findFirstVisibleItemPosition());
        e.putInt("scrollPos2", ((LinearLayoutManager)grid2.getLayoutManager()).findFirstVisibleItemPosition());
        e.apply();
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == PERMISSION_CODE && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) saveToGallery();
        else Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_about) {
            showAboutDialog();
            return true;
        } else if (id == R.id.action_clear_cache) {
            cache.clearCache();
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAboutDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_about, null);
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("About")
            .setView(view)
            .setPositiveButton("OK", null)
            .show();
    }

    private void vibrate(int feedbackConstant) {
        if (resultImage != null) {
            resultImage.performHapticFeedback(feedbackConstant);
        }
    }

    private void showSizePopup(SeekBar sb, int progress) {
        int size = normalizeSize(progress);
        sizePopupText.setText(size + "px");
        if (!sizePopup.isShowing()) {
            int[] location = new int[2];
            sb.getLocationInWindow(location);
            float thumbX = (float) progress / sb.getMax() * sb.getWidth();
            sizePopup.showAtLocation(sb, Gravity.NO_GRAVITY,
                (int)(location[0] + thumbX - sizePopupText.getWidth()/2),
                location[1] - POPUP_OFFSET_Y);
        } else {
            int[] location = new int[2];
            sb.getLocationInWindow(location);
            float thumbX = (float) progress / sb.getMax() * sb.getWidth();
            sizePopup.update((int)(location[0] + thumbX - sizePopup.getWidth()/2),
                           location[1] - sizePopup.getHeight() - 20,
                           -1, -1);
        }
    }

    private void initializeSlider() {
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) {
                    pendingRequestSize = normalizeSize(p);
                    updateHandler.removeCallbacks(updateRunnable);
                    updateRunnable = () -> updateResult();
                    updateHandler.postDelayed(updateRunnable, UPDATE_DELAY);
                    
                    showSizePopup(sb, p);
                }
            }
            
            public void onStartTrackingTouch(SeekBar sb) {
                showSizePopup(sb, sb.getProgress());
            }
            
            public void onStopTrackingTouch(SeekBar sb) {
                if (sizePopup.isShowing()) {
                    sizePopup.dismiss();
                }
                updateHandler.removeCallbacks(updateRunnable);
                updateResult();
                saveState();
            }
        });

        // Add tap listener for the slider
        slider.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                SeekBar sb = (SeekBar)v;
                int width = sb.getWidth() - sb.getPaddingLeft() - sb.getPaddingRight();
                float x = event.getX() - sb.getPaddingLeft();
                float progress = x / width * sb.getMax();
                showSizePopup(sb, (int)progress);
                return false;
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                if (sizePopup.isShowing()) {
                    sizePopup.dismiss();
                }
            }
            return false;
        });
    }

    private void initializeButtons() {
        shareBtn.setOnClickListener(v -> shareImage());
        saveBtn.setOnClickListener(v -> checkPermissionsAndSave());
        copyUrlBtn.setOnClickListener(v -> copyUrlToClipboard());
        
        // Initially disable buttons until an emoji pair is selected
        shareBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        slider.setEnabled(false);
        copyUrlBtn.setEnabled(false);
    }
}