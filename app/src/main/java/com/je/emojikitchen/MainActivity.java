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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.appbar.MaterialToolbar;

import java.io.*;
import java.util.*;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "EmojiKitchenPrefs";
    private static final String API = "https://emk.vercel.app/s/%s_%s?size=%d";
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
    private static final int[] SIZES = {32, 64, 128, 256, 512};
    private static final int DEFAULT_SIZE_INDEX = 2;

    private RecyclerView grid1, grid2;
    private EmojiRecyclerAdapter adapter1, adapter2;
    private ImageView resultImage;
    private RangeSlider slider;
    private Button shareBtn, saveBtn, copyUrlBtn;
    private ImageButton customSizeButton1, customSizeButton2;
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
    private int customSize = -1;
    private LinearLayout sliderRow, customSizeRow;
    private Button resetSizeButton;
    private TextView customSizeInUseText;

    @Override 
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);  // This makes it work as ActionBar

        cleanShareCache();

        if (saved != null) {
            emoji1 = saved.getString("emoji1");
            emoji2 = saved.getString("emoji2");
        }
        
        emojis = getResources().getStringArray(R.array.emoji_codes);
        grid1 = findViewById(R.id.emojiGrid1);
        grid2 = findViewById(R.id.emojiGrid2);
        resultImage = findViewById(R.id.resultImage);
        resultImage.setBackgroundResource(R.drawable.emoji_grid_background);
        shareBtn = findViewById(R.id.shareButton);
        saveBtn = findViewById(R.id.saveButton);
        copyUrlBtn = findViewById(R.id.copyUrlButton);
        customSizeButton1 = findViewById(R.id.customSizeButton1);
        customSizeButton2 = findViewById(R.id.customSizeButton2);
        loading = findViewById(R.id.loadingIndicator);
        cache = EmojiCache.getInstance(this);

        sliderRow = findViewById(R.id.sliderRow);
        customSizeRow = findViewById(R.id.customSizeRow);
        resetSizeButton = findViewById(R.id.resetSizeButton);
        customSizeInUseText = findViewById(R.id.customSizeInUseText);

        initializeGrids();
        initializeSizePopup();
        initializeSlider();
        initializeButtons();
        initializeCustomSizeButton();
        initializeResetSizeButton();
        updateSizeControls();
        restoreState();
    }

    private void cleanShareCache() {
        File tempDir = new File(getCacheDir(), "emoji_cache");
        if (tempDir.exists() && tempDir.isDirectory()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    if (name.endsWith("_tmp_share.png")) {
                        file.delete();
                    }
                }
            }
        }
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

    private int getSliderIndexForSize(int size) {
        int index = 0;
        int minDiff = Math.abs(SIZES[0] - size);
        for (int i = 1; i < SIZES.length; i++) {
            int diff = Math.abs(SIZES[i] - size);
            if (diff < minDiff) {
                minDiff = diff;
                index = i;
            }
        }
        return index;
    }

    private int getSizeFromSlider() {
        if (customSize > 0) return customSize;
        float value = slider.getValues().get(0);
        int index = Math.round(value);
        index = Math.max(0, Math.min(index, SIZES.length - 1));
        return SIZES[index];
    }

    private void updateResult() {
        if (emoji1 == null || emoji2 == null) return;
        int reqSize = pendingRequestSize > 0 ? pendingRequestSize : getSizeFromSlider();
        pendingRequestSize = 0;

        Bitmap cached = cache.loadFromCache(emoji1, emoji2, -1);
        if (cached != null) {
            int cachedSize = Math.max(cached.getWidth(), cached.getHeight());
            if (reqSize <= cachedSize) {
                Bitmap toShow = (reqSize == cachedSize) ? cached
                        : Bitmap.createScaledBitmap(cached, reqSize, reqSize, true);
                setImage(toShow);
                return;
            }
        }
        currentRequestSize = reqSize;
        loadFromNetwork(reqSize);
    }

    private void loadFromNetwork(int size) {
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
                Toast.makeText(this, getString(R.string.network_error, error), Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, R.string.error_combo_not_exists, Toast.LENGTH_SHORT).show();
        updateButtonStates(false);
        doubleVibrate();
    }

    private void updateButtonStates(boolean enabled) {
        shareBtn.setEnabled(enabled);
        saveBtn.setEnabled(enabled);
        slider.setEnabled(enabled);
        copyUrlBtn.setEnabled(true);
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
                        Bitmap cached = cache.loadFromCache(emoji1, emoji2, -1);
                        int bmpSize = Math.max(bmp.getWidth(), bmp.getHeight());
                        int cachedSize = cached != null ? Math.max(cached.getWidth(), cached.getHeight()) : -1;
                        if (cached == null || bmpSize > cachedSize) {
                            cache.saveToCache(emoji1, emoji2, bmp);
                        }
                        setImage(bmp);
                        hideLoading();
                        vibrate(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                }
                public void onLoadCleared(Drawable placeholder) { 
                    hideLoading(); 
                }
                public void onLoadFailed(Drawable e) {
                    if (requestId == currentRequestId) {
                        hideLoading();
                        Toast.makeText(MainActivity.this, R.string.error_load_failed, Toast.LENGTH_SHORT).show();
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

        String key = String.format("emoji_%s_%s", emoji1, emoji2);
        File cacheFile = new File(getCacheDir(), "emoji_cache/" + key + ".png");
        int requestedSize = getSizeFromSlider();

        if (!cacheFile.exists()) {
            Log.e("MainActivity", "cache file does not exist: " + cacheFile.getAbsolutePath());
            Toast.makeText(this, R.string.error_image_not_available, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Bitmap cached = cache.loadFromCache(emoji1, emoji2, -1);
            if (cached == null) {
                Toast.makeText(this, R.string.error_image_not_available, Toast.LENGTH_SHORT).show();
                return;
            }
            int cachedSize = Math.max(cached.getWidth(), cached.getHeight());
            File fileToShare;
            boolean isTemp = false;
            if (requestedSize == cachedSize) {
                fileToShare = cacheFile;
            } else {
                Bitmap resized = Bitmap.createScaledBitmap(cached, requestedSize, requestedSize, true);
                fileToShare = new File(getCacheDir(), "emoji_cache/" + key + "_tmp_share.png");
                try (FileOutputStream out = new FileOutputStream(fileToShare)) {
                    resized.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                isTemp = true;
            }

            String authority = getPackageName() + ".fileprovider";
            Log.d("MainActivity", "FileProvider authority: " + authority);
            Log.d("MainActivity", "File to share path: " + fileToShare.getAbsolutePath());

            Uri uri = FileProvider.getUriForFile(this, authority, fileToShare);
            if (uri == null) {
                throw new IllegalArgumentException("Failed to create content URI");
            }

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(IMAGE_MIME_TYPE);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(Intent.createChooser(intent, getString(R.string.share_chooser_title)));
            } else {
                Toast.makeText(this, R.string.error_no_share_apps, Toast.LENGTH_SHORT).show();
                if (isTemp) fileToShare.delete();
                return;
            }

            if (isTemp) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> fileToShare.delete(), 3000);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Failed to share image", e);
            Toast.makeText(this, getString(R.string.error_share_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void saveToGallery() {
        if (emoji1 == null || emoji2 == null) return;

        String key = String.format("emoji_%s_%s", emoji1, emoji2);
        File cacheFile = new File(getCacheDir(), "emoji_cache/" + key + ".png");
        int requestedSize = getSizeFromSlider();

        if (!cacheFile.exists()) {
            Toast.makeText(this, R.string.error_image_not_available, Toast.LENGTH_SHORT).show();
            return;
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "EmojiKitchen");
        dir.mkdirs();
        File destFile = new File(dir, key + ".png");

        try {
            Bitmap cached = cache.loadFromCache(emoji1, emoji2, -1);
            if (cached == null) {
                Toast.makeText(this, R.string.error_image_not_available, Toast.LENGTH_SHORT).show();
                return;
            }
            int cachedSize = Math.max(cached.getWidth(), cached.getHeight());
            if (requestedSize == cachedSize) {

                try (FileInputStream in = new FileInputStream(cacheFile);
                     FileOutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            } else {
                Bitmap resized = Bitmap.createScaledBitmap(cached, requestedSize, requestedSize, true);
                try (FileOutputStream out = new FileOutputStream(destFile)) {
                    resized.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }

            MediaScannerConnection.scanFile(this, new String[]{destFile.toString()}, null,
                (p, uri) -> runOnUiThread(() -> Toast.makeText(this, R.string.success_saved_gallery, Toast.LENGTH_SHORT).show()));

        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, R.string.error_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void copyUrlToClipboard() {
        if (emoji1 == null || emoji2 == null) return;
        int size = getSizeFromSlider();
        String url = String.format(API, emoji1, emoji2, size);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Emoji Kitchen URL", url);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, R.string.success_url_copied, Toast.LENGTH_SHORT).show();
    }

    private void restoreState() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        emoji1 = p.getString("selectedEmoji1", null);
        emoji2 = p.getString("selectedEmoji2", null);
        float sliderValue = p.getFloat("sliderValue", DEFAULT_SIZE_INDEX);
        slider.setValues(sliderValue);
        customSize = p.getInt("customSize", -1);

        updateSizeControls();

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

            int size = getSizeFromSlider();
            Bitmap cached = cache.loadFromCache(emoji1, emoji2, -1);
            if (cached != null) {
                int cachedSize = Math.max(cached.getWidth(), cached.getHeight());
                Bitmap toShow = (size <= cachedSize)
                        ? (size == cachedSize ? cached : Bitmap.createScaledBitmap(cached, size, size, true))
                        : null;
                if (toShow != null) {
                    setImage(toShow);
                } else {
                    updateResult();
                }
            } else {
                updateResult();
            }
        }
    }

    private void updateSizeControls() {
        if (customSize > 0) {
            sliderRow.setVisibility(View.GONE);
            customSizeRow.setVisibility(View.VISIBLE);
            customSizeInUseText.setText(getString(R.string.custom_size_in_use, customSize));
        } else {
            sliderRow.setVisibility(View.VISIBLE);
            customSizeRow.setVisibility(View.GONE);
        }
    }

    private void initializeResetSizeButton() {
        resetSizeButton.setOnClickListener(v -> {
            customSize = -1;
            slider.setValues((float) DEFAULT_SIZE_INDEX);
            updateSizeControls();
            updateResult();
            saveState();
        });
    }

    private void showCustomSizeDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_custom_size, null);
        TextInputEditText sizeInput = view.findViewById(R.id.sizeInput);

        builder.setTitle(R.string.custom_size_title)
                .setView(view)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    try {
                        String input = sizeInput.getText().toString();
                        int size = Integer.parseInt(input);
                        size = Math.max(1, Math.min(512, size));
                        customSize = size;
                        updateSizeControls();
                        updateResult();
                        saveState();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, R.string.error_invalid_size, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void saveState() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (emoji1 != null) e.putString("selectedEmoji1", emoji1);
        if (emoji2 != null) e.putString("selectedEmoji2", emoji2);
        e.putFloat("sliderValue", slider.getValues().get(0));
        e.putInt("customSize", customSize);
        e.putInt("scrollPos1", ((LinearLayoutManager)grid1.getLayoutManager()).findFirstVisibleItemPosition());
        e.putInt("scrollPos2", ((LinearLayoutManager)grid2.getLayoutManager()).findFirstVisibleItemPosition());
        e.apply();
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == PERMISSION_CODE && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) saveToGallery();
        else Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
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
            cleanShareCache();
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAboutDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_about, null);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .show();
    }

    private void vibrate(int feedbackConstant) {
        if (resultImage != null) {
            resultImage.performHapticFeedback(feedbackConstant);
        }
    }

    private void showSizePopup(SeekBar sb, int progress) {
        int size = normalizeSize(progress);
        sizePopupText.setText(getString(R.string.size_pixels, size));
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
        slider = findViewById(R.id.sizeSlider);
        slider.setValues((float) DEFAULT_SIZE_INDEX);
        slider.setStepSize(1f);
        slider.setLabelFormatter(value -> {
            int index = Math.max(0, Math.min(Math.round(value), SIZES.length - 1));
            return SIZES[index] + "px";
        });

        slider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                int index = Math.max(0, Math.min(Math.round(value), SIZES.length - 1));
                pendingRequestSize = SIZES[index];
                customSize = -1;
                updateSizeControls();
                updateHandler.removeCallbacks(updateRunnable);
                updateRunnable = this::updateResult;
                updateHandler.postDelayed(updateRunnable, UPDATE_DELAY);
                vibrate(HapticFeedbackConstants.CLOCK_TICK);
            }
        });

        slider.addOnSliderTouchListener(new RangeSlider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(RangeSlider slider) {}

            @Override
            public void onStopTrackingTouch(RangeSlider slider) {
                updateHandler.removeCallbacks(updateRunnable);
                updateResult();
                saveState();
            }
        });
    }

    private void initializeButtons() {
        shareBtn.setOnClickListener(v -> shareImage());
        saveBtn.setOnClickListener(v -> checkPermissionsAndSave());
        copyUrlBtn.setOnClickListener(v -> copyUrlToClipboard());
        
        shareBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        slider.setEnabled(false);
        copyUrlBtn.setEnabled(false);
    }

    private void initializeCustomSizeButton() {
        if (customSizeButton1 != null) {
            customSizeButton1.setOnClickListener(v -> showCustomSizeDialog());
        }
        if (customSizeButton2 != null) {
            customSizeButton2.setOnClickListener(v -> showCustomSizeDialog());
        }
    }
}
