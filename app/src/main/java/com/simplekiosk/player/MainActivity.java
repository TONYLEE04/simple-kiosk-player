package com.simplekiosk.player;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
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
import java.util.Calendar;
import java.util.List;

public final class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String BASE_DIR_NAME = "SimpleKiosk";
    private static final String ACTION_WAKE_PLAYBACK = "com.simplekiosk.player.WAKE_PLAYBACK";
    private static final int WAKE_ALARM_REQUEST_CODE = 1001;
    private static final long CONFIG_RELOAD_INTERVAL_MS = 5000L;
    private static final long SCHEDULE_CHECK_INTERVAL_MS = 60000L;

    private final Handler handler = new Handler();
    private final Runnable nextRunnable = new Runnable() {
        @Override
        public void run() {
            playNext();
        }
    };
    private final Runnable configReloadRunnable = new Runnable() {
        @Override
        public void run() {
            checkConfigReload();
            handler.postDelayed(this, CONFIG_RELOAD_INTERVAL_MS);
        }
    };
    private final Runnable scheduleCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkScheduleChange();
            handler.postDelayed(this, SCHEDULE_CHECK_INTERVAL_MS);
        }
    };

    private File baseDir;
    private PlayerLog log;
    private ConfigLoader configLoader;
    private PlayerConfig config;
    private List<PlaylistItem> activePlaylist;
    private ScheduleEntry activeSchedule;
    private String activePlaylistName = "";
    private boolean activeSilent;
    private long configLastModified;
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
        configLoader = new ConfigLoader(baseDir);

        buildViews();
        loadAndStart();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && ACTION_WAKE_PLAYBACK.equals(intent.getAction())) {
            log.info("Received wake playback intent");
            wakeScreenBriefly();
            handleWakePlayback("wake alarm");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySystemUiFlags();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlaybackTimers();
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
            config = configLoader.load();
            configLastModified = configLoader.getConfigLastModified();
            applyConfig();
            startPlaybackTimers();
            log.info("Loaded config from " + configLoader.getConfigFile().getAbsolutePath());
            if (selectActivePlayback(true, "initial load")) {
                playNext();
            }
        } catch (Exception e) {
            showError("Simple Kiosk config error\n\n" + e.getMessage());
            log.error("Could not load config", e);
        }
    }

    private void startPlaybackTimers() {
        handler.removeCallbacks(configReloadRunnable);
        handler.removeCallbacks(scheduleCheckRunnable);
        handler.postDelayed(configReloadRunnable, CONFIG_RELOAD_INTERVAL_MS);
        handler.postDelayed(scheduleCheckRunnable, SCHEDULE_CHECK_INTERVAL_MS);
    }

    private void stopPlaybackTimers() {
        handler.removeCallbacks(nextRunnable);
        handler.removeCallbacks(configReloadRunnable);
        handler.removeCallbacks(scheduleCheckRunnable);
    }

    private void checkConfigReload() {
        long lastModified = configLoader.getConfigLastModified();
        if (lastModified == 0L || lastModified == configLastModified) {
            return;
        }

        try {
            PlayerConfig reloadedConfig = configLoader.load();
            config = reloadedConfig;
            configLastModified = lastModified;
            applyConfig();
            log.info("Reloaded config after file change");
            if (selectActivePlayback(true, "config reload")) {
                playNext();
            }
        } catch (Exception e) {
            log.error("Ignoring invalid hot-reloaded config", e);
        }
    }

    private void checkScheduleChange() {
        if (config == null || config.schedules.isEmpty()) {
            return;
        }
        if (selectActivePlayback(false, "schedule check")) {
            playNext();
        }
    }


    private void wakeScreenBriefly() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "SimpleKiosk:WakePlayback");
            wakeLock.acquire(10000L);
            log.info("Acquired wake playback wakelock");
        } catch (RuntimeException e) {
            log.error("Could not acquire wake playback wakelock", e);
        }
    }
    private void handleWakePlayback(String reason) {
        startPlaybackTimers();
        if (config == null) {
            loadAndStart();
            return;
        }
        if (selectActivePlayback(false, reason)) {
            playNext();
        } else if (!activeSilent && activePlaylist != null && mediaPlayer == null) {
            playNext();
        }
    }

    private boolean selectActivePlayback(boolean forceReset, String reason) {
        if (config == null) {
            activePlaylist = null;
            activeSchedule = null;
            activePlaylistName = "";
            activeSilent = false;
            return false;
        }

        Calendar now = Calendar.getInstance();
        ScheduleEntry selectedSchedule = config.getActiveSchedule(now);
        if (selectedSchedule != null && selectedSchedule.isSilent()) {
            boolean changed = forceReset || !activeSilent || activeSchedule != selectedSchedule;
            if (changed) {
                enterSilentMode(selectedSchedule, reason);
            }
            return false;
        }

        List<PlaylistItem> selectedPlaylist = selectedSchedule != null
                ? selectedSchedule.playlist
                : config.playlist;
        String selectedName = selectedSchedule != null ? selectedSchedule.name : "default";
        if (selectedPlaylist == null || selectedPlaylist.isEmpty()) {
            activePlaylist = selectedPlaylist;
            activeSchedule = selectedSchedule;
            activePlaylistName = selectedName;
            activeSilent = false;
            showError("No active playlist for current time");
            log.error("No active playlist for current time");
            return false;
        }

        boolean changed = forceReset || activeSilent || activeSchedule != selectedSchedule
                || activePlaylist != selectedPlaylist || !selectedName.equals(activePlaylistName);
        if (changed) {
            activePlaylist = selectedPlaylist;
            activeSchedule = selectedSchedule;
            activePlaylistName = selectedName;
            activeSilent = false;
            playlistIndex = -1;
            cancelWakeAlarm();
            applyPlaybackScreenPolicy();
            log.info("Selected playlist '" + activePlaylistName + "' by " + reason
                    + " with " + activePlaylist.size() + " items");
        }
        return changed;
    }

    private void enterSilentMode(ScheduleEntry schedule, String reason) {
        activePlaylist = null;
        activeSchedule = schedule;
        activePlaylistName = schedule.name;
        activeSilent = true;
        playlistIndex = -1;
        pendingVideoItem = null;
        handler.removeCallbacks(nextRunnable);
        releaseMediaPlayer();

        imageView.setImageDrawable(null);
        imageView.setVisibility(View.GONE);
        textureView.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        root.setBackgroundColor(0xff000000);

        if (schedule.shouldAllowSleep()) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            scheduleWakeAlarmForNextPlayback(schedule);
        } else {
            cancelWakeAlarm();
            if (config.keepScreenOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }
        applySystemUiFlags();
        log.info("Selected silent schedule '" + schedule.name + "' by " + reason
                + " screen=" + schedule.screen);
    }

    private void applyPlaybackScreenPolicy() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (config.keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void scheduleWakeAlarmForNextPlayback(ScheduleEntry silentSchedule) {
        long triggerAtMillis = calculateNextPlaybackWakeMillis(silentSchedule);
        if (triggerAtMillis <= 0L) {
            log.error("Could not calculate wake alarm for silent schedule: " + silentSchedule.name);
            return;
        }

        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        PendingIntent pendingIntent = createWakePendingIntent();
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        }
        log.info("Scheduled wake alarm at " + triggerAtMillis + " for silent schedule '"
                + silentSchedule.name + "'");
    }

    private long calculateNextPlaybackWakeMillis(ScheduleEntry silentSchedule) {
        Calendar now = Calendar.getInstance();
        Calendar best = null;

        if (config != null) {
            for (int i = 0; i < config.schedules.size(); i++) {
                ScheduleEntry schedule = config.schedules.get(i);
                if (schedule.isSilent()) {
                    continue;
                }
                Calendar candidate = calendarAtMinute(schedule.startMinute);
                if (!candidate.after(now)) {
                    candidate.add(Calendar.DATE, 1);
                }
                if (best == null || candidate.before(best)) {
                    best = candidate;
                }
            }
        }

        if (best == null && config != null && !config.playlist.isEmpty()) {
            best = calendarAtMinute(silentSchedule.endMinute);
            if (!best.after(now)) {
                best.add(Calendar.DATE, 1);
            }
        }

        return best != null ? best.getTimeInMillis() : 0L;
    }

    private Calendar calendarAtMinute(int minuteOfDay) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        calendar.set(Calendar.MINUTE, minuteOfDay % 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    private PendingIntent createWakePendingIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(ACTION_WAKE_PLAYBACK);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, WAKE_ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void cancelWakeAlarm() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmManager.cancel(createWakePendingIntent());
    }

    private void applyConfig() {
        root.setBackgroundColor(config.backgroundColor);

        if ("portrait".equals(config.orientation)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else if ("landscape".equals(config.orientation)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }

        if (!activeSilent) {
            applyPlaybackScreenPolicy();
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

        if (config == null) {
            showError("Config is not loaded");
            return;
        }
        if (activeSilent) {
            return;
        }
        if (activePlaylist == null || activePlaylist.isEmpty()) {
            selectActivePlayback(true, "playback");
        }
        if (activeSilent) {
            return;
        }
        if (activePlaylist == null || activePlaylist.isEmpty()) {
            showError("Playlist is empty");
            return;
        }

        playlistIndex = (playlistIndex + 1) % activePlaylist.size();
        PlaylistItem item = activePlaylist.get(playlistIndex);
        log.info("Playing " + item.type + " from playlist '" + activePlaylistName + "': "
                + item.file.getAbsolutePath());

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
        activeSilent = false;
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
        if (pendingVideoItem != null && !activeSilent) {
            startVideo(pendingVideoItem, surface);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        if (mediaPlayer != null && pendingVideoItem != null && !activeSilent) {
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
