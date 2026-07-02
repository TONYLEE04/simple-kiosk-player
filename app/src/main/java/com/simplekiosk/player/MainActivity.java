package com.simplekiosk.player;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;

public final class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String BASE_DIR_NAME = "SimpleKiosk";

    private final Handler handler = new Handler();
    private final Runnable nextRunnable = new Runnable() {
        @Override
        public void run() {
            playNext();
        }
    };

    private File baseDir;
    private PlayerLog log;
    private PlayerConfig config;
    private FrameLayout root;
    private ImageView imageView;
    private TextureView textureView;
    private TextView errorView;
    private MediaPlayer mediaPlayer;
    private int playlistIndex = -1;
    private PlaylistItem pendingVideoItem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        baseDir = new File(Environment.getExternalStorageDirectory(), BASE_DIR_NAME);
        log = new PlayerLog(baseDir);

        buildViews();
        loadAndStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySystemUiFlags();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(nextRunnable);
        releaseMediaPlayer();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void buildViews() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);

        imageView = new ImageView(this);
        imageView.setBackgroundColor(0xff000000);
        imageView.setVisibility(View.GONE);
        root.addView(imageView, fullScreenParams());

        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);
        textureView.setVisibility(View.GONE);
        root.addView(textureView, fullScreenParams());

        errorView = new TextView(this);
        errorView.setGravity(Gravity.CENTER);
        errorView.setTextColor(0xffffffff);
        errorView.setTextSize(20);
        errorView.setPadding(32, 32, 32, 32);
        errorView.setVisibility(View.GONE);
        root.addView(errorView, fullScreenParams());

        setContentView(root);
    }

    private FrameLayout.LayoutParams fullScreenParams() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private void loadAndStart() {
        try {
            ConfigLoader loader = new ConfigLoader(baseDir);
            config = loader.load();
            applyConfig();
            log.info("Loaded config with " + config.playlist.size() + " playlist items");
            playNext();
        } catch (Exception e) {
            showError("Simple Kiosk config error\n\n" + e.getMessage());
            log.error("Could not load config", e);
        }
    }

    private void applyConfig() {
        root.setBackgroundColor(config.backgroundColor);

        if ("portrait".equals(config.orientation)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else if ("landscape".equals(config.orientation)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }

        if (config.keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        applySystemUiFlags();
    }

    private void applySystemUiFlags() {
        if (config == null || !config.hideSystemUi) {
            return;
        }

        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        root.setSystemUiVisibility(flags);
    }

    private void playNext() {
        handler.removeCallbacks(nextRunnable);
        releaseMediaPlayer();

        if (config == null || config.playlist.isEmpty()) {
            showError("Playlist is empty");
            return;
        }

        playlistIndex = (playlistIndex + 1) % config.playlist.size();
        PlaylistItem item = config.playlist.get(playlistIndex);
        log.info("Playing " + item.type + ": " + item.file.getAbsolutePath());

        if (item.isImage()) {
            playImage(item);
        } else if (item.isVideo()) {
            playVideo(item);
        } else {
            showError("Unsupported playlist item type: " + item.type);
        }
    }

    private void playImage(PlaylistItem item) {
        pendingVideoItem = null;
        textureView.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);

        Bitmap bitmap = decodeBitmapForScreen(item.file);
        if (bitmap == null) {
            showError("Could not decode image\n\n" + item.file.getAbsolutePath());
            log.error("Could not decode image: " + item.file.getAbsolutePath());
            return;
        }

        imageView.setScaleType(scaleTypeForFitMode(item.fitMode));
        imageView.setImageBitmap(bitmap);
        imageView.setVisibility(View.VISIBLE);
        handler.postDelayed(nextRunnable, item.durationSeconds * 1000L);
    }

    private Bitmap decodeBitmapForScreen(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int targetWidth = root.getWidth() > 0 ? root.getWidth() : metrics.widthPixels;
        int targetHeight = root.getHeight() > 0 ? root.getHeight() : metrics.heightPixels;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private int calculateSampleSize(int width, int height, int targetWidth, int targetHeight) {
        int sampleSize = 1;
        while ((width / sampleSize) > targetWidth * 2 || (height / sampleSize) > targetHeight * 2) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private ImageView.ScaleType scaleTypeForFitMode(String fitMode) {
        if (PlayerConfig.FIT_COVER.equals(fitMode)) {
            return ImageView.ScaleType.CENTER_CROP;
        }
        if (PlayerConfig.FIT_STRETCH.equals(fitMode)) {
            return ImageView.ScaleType.FIT_XY;
        }
        if (PlayerConfig.FIT_CENTER.equals(fitMode)) {
            return ImageView.ScaleType.CENTER;
        }
        return ImageView.ScaleType.FIT_CENTER;
    }

    private void playVideo(PlaylistItem item) {
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        textureView.setVisibility(View.VISIBLE);

        pendingVideoItem = item;
        if (textureView.isAvailable()) {
            startVideo(item, textureView.getSurfaceTexture());
        }
    }

    private void startVideo(final PlaylistItem item, SurfaceTexture surfaceTexture) {
        releaseMediaPlayer();

        Surface surface = new Surface(surfaceTexture);
        MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;
        try {
            player.setDataSource(item.file.getAbsolutePath());
            player.setSurface(surface);
            player.setLooping(false);
            if (config.mute) {
                player.setVolume(0f, 0f);
            }
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer preparedPlayer) {
                    applyVideoTransform(preparedPlayer.getVideoWidth(), preparedPlayer.getVideoHeight(), item.fitMode);
                    preparedPlayer.start();
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer completedPlayer) {
                    playNext();
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer failedPlayer, int what, int extra) {
                    showError("Video playback failed\n\n" + item.file.getAbsolutePath());
                    log.error("Video playback failed: what=" + what + " extra=" + extra);
                    return true;
                }
            });
            player.prepareAsync();
        } catch (IOException e) {
            showError("Could not open video\n\n" + item.file.getAbsolutePath());
            log.error("Could not open video: " + item.file.getAbsolutePath(), e);
        } catch (IllegalArgumentException e) {
            showError("Unsupported video\n\n" + item.file.getAbsolutePath());
            log.error("Unsupported video: " + item.file.getAbsolutePath(), e);
        } finally {
            surface.release();
        }
    }

    private void applyVideoTransform(int videoWidth, int videoHeight, String fitMode) {
        int containerWidth = root.getWidth();
        int containerHeight = root.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            containerWidth = metrics.widthPixels;
            containerHeight = metrics.heightPixels;
        }

        if (videoWidth <= 0 || videoHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) {
            textureView.setTransform(null);
            return;
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) textureView.getLayoutParams();
        params.gravity = Gravity.CENTER;
        textureView.setTransform(null);

        if (PlayerConfig.FIT_STRETCH.equals(fitMode)) {
            params.width = FrameLayout.LayoutParams.MATCH_PARENT;
            params.height = FrameLayout.LayoutParams.MATCH_PARENT;
        } else if (PlayerConfig.FIT_CENTER.equals(fitMode)) {
            params.width = videoWidth;
            params.height = videoHeight;
        } else {
            float scaleX = (float) containerWidth / (float) videoWidth;
            float scaleY = (float) containerHeight / (float) videoHeight;
            float scale = PlayerConfig.FIT_COVER.equals(fitMode)
                    ? Math.max(scaleX, scaleY)
                    : Math.min(scaleX, scaleY);
            params.width = Math.max(1, Math.round(videoWidth * scale));
            params.height = Math.max(1, Math.round(videoHeight * scale));
        }

        textureView.setLayoutParams(params);
        log.info("Video layout fitMode=" + fitMode
                + " video=" + videoWidth + "x" + videoHeight
                + " container=" + containerWidth + "x" + containerHeight
                + " texture=" + params.width + "x" + params.height);
    }
    private void showError(String message) {
        handler.removeCallbacks(nextRunnable);
        releaseMediaPlayer();
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.GONE);
        textureView.setVisibility(View.GONE);
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
        applySystemUiFlags();
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (RuntimeException ignored) {
            }
            mediaPlayer = null;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (pendingVideoItem != null) {
            startVideo(pendingVideoItem, surface);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        if (mediaPlayer != null && pendingVideoItem != null) {
            applyVideoTransform(mediaPlayer.getVideoWidth(), mediaPlayer.getVideoHeight(), pendingVideoItem.fitMode);
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        releaseMediaPlayer();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }
}
