package com.brentvatne.exoplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.app.ActivityManager;
import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.annotation.NonNull;

import com.brentvatne.react.R;
import com.brentvatne.receiver.AudioBecomingNoisyReceiver;
import com.brentvatne.receiver.BecomingNoisyListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Dynamic;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager;
import com.google.android.exoplayer2.drm.DrmSessionEventListener;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.DrmSessionManagerProvider;
import com.google.android.exoplayer2.drm.FrameworkMediaDrm;
import com.google.android.exoplayer2.drm.HttpMediaDrmCallback;
import com.google.android.exoplayer2.drm.UnsupportedDrmException;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.SingleSampleMediaSource;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.ui.PlayerControlView;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.source.dash.DashUtil;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.source.dash.manifest.Period;
import com.google.android.exoplayer2.source.dash.manifest.AdaptationSet;
import com.google.android.exoplayer2.source.dash.manifest.Representation;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;
import java.lang.Thread;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.lang.Integer;

@SuppressLint("ViewConstructor")
class ReactExoplayerView extends FrameLayout implements
        LifecycleEventListener,
        Player.Listener,
        BandwidthMeter.EventListener,
        BecomingNoisyListener,
        AudioManager.OnAudioFocusChangeListener,
        DrmSessionEventListener {

    public static final double DEFAULT_MAX_HEAP_ALLOCATION_PERCENT = 1;
    public static final double DEFAULT_MIN_BACK_BUFFER_MEMORY_RESERVE = 0;
    public static final double DEFAULT_MIN_BUFFER_MEMORY_RESERVE = 0;

    private static final String TAG = "ReactExoplayerView";

    private static final CookieManager DEFAULT_COOKIE_MANAGER;
    private static final int SHOW_PROGRESS = 1;

    static {
        DEFAULT_COOKIE_MANAGER = new CookieManager();
        DEFAULT_COOKIE_MANAGER.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    }

    private static final Map<Integer, ReactExoplayerView> instances = new HashMap<>();
    private FullScreenDelegate fullScreenDelegate;

    private final VideoEventEmitter eventEmitter;
    private final ReactExoplayerConfig config;
    private final DefaultBandwidthMeter bandwidthMeter;
    private PlayerControlView playerControlView;
    private View playPauseControlContainer;
    private Player.Listener eventListener;

    private ExoPlayerView exoPlayerView;

    private DataSource.Factory mediaDataSourceFactory;
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private boolean playerNeedsSource;

    private int resumeWindow;
    private long resumePosition;
    private boolean loadVideoStarted;
    private boolean isFullscreen;
    private String fullScreenOrientation;
    private boolean isInFullscreen;
    private boolean isInBackground;
    private boolean isPaused;
    private boolean isBuffering;
    private boolean muted = false;
    private boolean hasAudioFocus = false;
    private float rate = 1f;
    private float audioVolume = 1f;
    private int minLoadRetryCount = 3;
    private int maxBitRate = 0;
    private long seekTime = C.TIME_UNSET;
    private boolean hasDrmFailed = false;
    private boolean isUsingContentResolution = false;
    private boolean selectTrackWhenReady = false;

    private int minBufferMs = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS;
    private int maxBufferMs = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS;
    private int bufferForPlaybackMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS;
    private int bufferForPlaybackAfterRebufferMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS;
    private double maxHeapAllocationPercent = ReactExoplayerView.DEFAULT_MAX_HEAP_ALLOCATION_PERCENT;
    private double minBackBufferMemoryReservePercent = ReactExoplayerView.DEFAULT_MIN_BACK_BUFFER_MEMORY_RESERVE;
    private double minBufferMemoryReservePercent = ReactExoplayerView.DEFAULT_MIN_BUFFER_MEMORY_RESERVE;
    // private Handler mainHandler;

    // Props from React
    private int backBufferDurationMs = DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS;
    private Uri srcUri;
    private String extension;
    private boolean repeat;
    private String audioTrackType;
    private Dynamic audioTrackValue;
    private String videoTrackType;
    private Dynamic videoTrackValue;
    private String textTrackType;
    private Dynamic textTrackValue;
    private ReadableArray textTracks;
    private boolean disableFocus;
    private boolean disableBuffering;
    private long contentStartTime = -1L;
    private boolean disableDisconnectError;
    private boolean preventsDisplaySleepDuringVideoPlayback = true;
    private float mProgressUpdateInterval = 250.0f;
    private boolean playInBackground = false;
    private Map<String, String> requestHeaders;
    private boolean mReportBandwidth = false;
    private UUID drmUUID = null;
    private String drmLicenseUrl = null;
    private String[] drmLicenseHeader = null;
    private boolean controls;
    // \ End props

    // React
    private final ThemedReactContext themedReactContext;
    private final AudioManager audioManager;
    private final AudioBecomingNoisyReceiver audioBecomingNoisyReceiver;

    // store last progress event values to avoid sending unnecessary messages
    private long lastPos = -1;
    private long lastBufferDuration = -1;
    private long lastDuration = -1;

    private final Handler progressHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == SHOW_PROGRESS) {
                if (player != null) {
                    long pos = player.getCurrentPosition();
                    long bufferedDuration = player.getBufferedPercentage() * player.getDuration() / 100;
                    long duration = player.getDuration();

                    if (lastPos != pos
                            || lastBufferDuration != bufferedDuration
                            || lastDuration != duration) {
                        lastPos = pos;
                        lastBufferDuration = bufferedDuration;
                        lastDuration = duration;
                        eventEmitter.progressChanged(pos, bufferedDuration, player.getDuration(), getPositionInFirstPeriodMsForCurrentWindow(pos));
                    }
                    msg = obtainMessage(SHOW_PROGRESS);
                    sendMessageDelayed(msg, Math.round(mProgressUpdateInterval));
                }
            }
        }
    };

    public double getPositionInFirstPeriodMsForCurrentWindow(long currentPosition) {
        Timeline.Window window = new Timeline.Window();
        if(!player.getCurrentTimeline().isEmpty()) {
            player.getCurrentTimeline().getWindow(player.getCurrentMediaItemIndex(), window);
        }
        return window.windowStartTimeMs + currentPosition;
    }

    public ReactExoplayerView(ThemedReactContext context, ReactExoplayerConfig config) {
        super(context);
        this.themedReactContext = context;
        this.eventEmitter = new VideoEventEmitter(context);
        this.config = config;
        this.bandwidthMeter = config.getBandwidthMeter();

        createViews();

        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        // themedReactContext.addLifecycleEventListener(this);
        audioBecomingNoisyReceiver = new AudioBecomingNoisyReceiver(themedReactContext);
    }


    @Override
    public void setId(int id) {
        super.setId(id);
        eventEmitter.setViewId(id);
    }

    private void createViews() {
        clearResumePosition();
        mediaDataSourceFactory = buildDataSourceFactory(true);
        if (CookieHandler.getDefault() != DEFAULT_COOKIE_MANAGER) {
            CookieHandler.setDefault(DEFAULT_COOKIE_MANAGER);
        }

        LayoutParams layoutParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        exoPlayerView = new ExoPlayerView(getContext());
        exoPlayerView.setLayoutParams(layoutParams);

        addView(exoPlayerView, 0, layoutParams);

        // mainHandler = new Handler();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        initializePlayer();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        /* We want to be able to continue playing audio when switching tabs.
         * Leave this here in case it causes issues.
         */
        // stopPlayback();
    }

    // LifecycleEventListener implementation

    @Override
    public void onHostResume() {
        if (!playInBackground || !isInBackground) {
            // setPlayWhenReady(!isPaused);
            if (isInFullscreen) {
                if (player != null) {
                    exoPlayerView.setPlayer(player);
                    syncPlayerState();
                }
                isInFullscreen = false;
            } else {
                setPlayWhenReady(!isPaused);
            }
        }
        isInBackground = false;
    }

    @Override
    public void onHostPause() {
        isInBackground = true;
        if (playInBackground) {
            return;
        }
        setPlayWhenReady(false);
    }

    @Override
    public void onHostDestroy() {
        stopPlayback();
    }

    public void cleanUpResources() {
        stopPlayback();
        instances.remove(this.getId());
    }

    //BandwidthMeter.EventListener implementation
    @Override
    public void onBandwidthSample(int elapsedMs, long bytes, long bitrate) {
        if (mReportBandwidth) {
            if (player == null) {
                eventEmitter.bandwidthReport(bitrate, 0, 0, "-1");
            } else {
                Format videoFormat = player.getVideoFormat();
                int width = videoFormat != null ? videoFormat.width : 0;
                int height = videoFormat != null ? videoFormat.height : 0;
                String trackId = videoFormat != null ? videoFormat.id : "-1";
                eventEmitter.bandwidthReport(bitrate, height, width, trackId);
            }
        }
    }

    public static ReactExoplayerView getViewInstance(Integer uid) {
        return instances.get(uid);
    }

    public ExoPlayer getPlayer() {
        return player;
    }

    public void syncPlayerState() {
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            // Try to get last frame displayed
            player.seekTo(player.getDuration() - 200);
            player.setPlayWhenReady(true);
        } else {
            player.setPlayWhenReady(!isPaused);
        }
    }

    public void registerFullScreenDelegate(FullScreenDelegate delegate) {
        this.fullScreenDelegate = delegate;
    }

    // Internal methods

    /**
     * Toggling the visibility of the player control view
     */
    private void togglePlayerControlVisibility() {
        if(player == null) return;
        reLayout(playerControlView);
        if (playerControlView.isVisible()) {
            playerControlView.hide();
        } else {
            playerControlView.show();
        }
    }

    private void showFullscreen() {
        instances.put(this.getId(), this);
        Intent intent = new Intent(getContext(), ExoPlayerFullscreenVideoActivity.class);
        intent.putExtra(ExoPlayerFullscreenVideoActivity.EXTRA_EXO_PLAYER_VIEW_ID, this.getId());
        intent.putExtra(ExoPlayerFullscreenVideoActivity.EXTRA_ORIENTATION, this.fullScreenOrientation);
        getContext().startActivity(intent);
        isInFullscreen = true;
    }


    /**
     * Initializing Player control
     */
    private void initializePlayerControl() {
        if (playerControlView == null) {
            playerControlView = new PlayerControlView(getContext());
        }

        // Setting the player for the playerControlView
        playerControlView.setPlayer(player);
        playerControlView.show();
        playPauseControlContainer = playerControlView.findViewById(R.id.exo_play_pause_container);
        playerControlView.findViewById(R.id.exo_fullscreen_button).setOnClickListener(v -> setFullscreen(true));

        // Invoking onClick event for exoplayerView
        exoPlayerView.setOnClickListener(v -> togglePlayerControlVisibility());

        //Handling the playButton click event
        ImageButton playButton = playerControlView.findViewById(R.id.exo_play);
        playButton.setOnClickListener(v -> {
            if (player != null && player.getPlaybackState() == Player.STATE_ENDED) {
                player.seekTo(0);
            }
            setPausedModifier(false);
        });

        //Handling the pauseButton click event
        ImageButton pauseButton = playerControlView.findViewById(R.id.exo_pause);
        pauseButton.setOnClickListener(v -> setPausedModifier(true));

        // Invoking onPlaybackStateChanged and onPlayWhenReadyChanged events for Player
        eventListener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                reLayout(playPauseControlContainer);
                //Remove this eventListener once its executed. since UI will work fine once after the reLayout is done
                player.removeListener(eventListener);
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                reLayout(playPauseControlContainer);
                //Remove this eventListener once its executed. since UI will work fine once after the reLayout is done
                player.removeListener(eventListener);
            }
        };
        player.addListener(eventListener);
    }

    /**
     * Adding Player control to the frame layout
     */
    private void addPlayerControl() {
        if(player == null) return;
        LayoutParams layoutParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        playerControlView.setLayoutParams(layoutParams);
        int indexOfPC = indexOfChild(playerControlView);
        if (indexOfPC != -1) {
            removeViewAt(indexOfPC);
        }
        addView(playerControlView, 1, layoutParams);
    }

    /**
     * Update the layout
     * @param view  view needs to update layout
     *
     * This is a workaround for the open bug in react-native: <a href="https://github.com/facebook/react-native/issues/17968">...</a>
     */
    private void reLayout(View view) {
        if (view == null) return;
        view.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
        view.layout(view.getLeft(), view.getTop(), view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private class RNVLoadControl extends DefaultLoadControl {
        private final int availableHeapInBytes;
        private final Runtime runtime;
        public RNVLoadControl(DefaultAllocator allocator, int minBufferMs, int maxBufferMs, int bufferForPlaybackMs, int bufferForPlaybackAfterRebufferMs, int targetBufferBytes, boolean prioritizeTimeOverSizeThresholds, int backBufferDurationMs, boolean retainBackBufferFromKeyframe) {
            super(allocator,
                    minBufferMs,
                    maxBufferMs,
                    bufferForPlaybackMs,
                    bufferForPlaybackAfterRebufferMs,
                    targetBufferBytes,
                    prioritizeTimeOverSizeThresholds,
                    backBufferDurationMs,
                    retainBackBufferFromKeyframe);
            runtime = Runtime.getRuntime();
            ActivityManager activityManager = (ActivityManager) themedReactContext.getSystemService(Context.ACTIVITY_SERVICE);
            availableHeapInBytes = (int) Math.floor(activityManager.getMemoryClass() * maxHeapAllocationPercent * 1024 * 1024);
        }

        @Override
        public boolean shouldContinueLoading(long playbackPositionUs, long bufferedDurationUs, float playbackSpeed) {
            if (ReactExoplayerView.this.disableBuffering) {
                return false;
            }
            int loadedBytes = getAllocator().getTotalBytesAllocated();
            boolean isHeapReached = availableHeapInBytes > 0 && loadedBytes >= availableHeapInBytes;
            if (isHeapReached) {
                return false;
            }
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            long freeMemory = runtime.maxMemory() - usedMemory;
            long reserveMemory = (long)minBufferMemoryReservePercent * runtime.maxMemory();
            long bufferedMs = bufferedDurationUs / (long)1000;
            if (reserveMemory > freeMemory && bufferedMs > 2000) {
                // We don't have enough memory in reserve so we stop buffering to allow other components to use it instead
                return false;
            }
            if (runtime.freeMemory() == 0) {
                Log.w("ExoPlayer Warning", "Free memory reached 0, forcing garbage collection");
                runtime.gc();
                return false;
            }
            return super.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed);
        }
    }

    private void startBufferCheckTimer() {
        // Handler mainHandler = this.mainHandler;
    }

    private void initializePlayer() {
        themedReactContext.addLifecycleEventListener(this);
        ReactExoplayerView self = this;
        Activity activity = themedReactContext.getCurrentActivity();
        // This ensures all props have been settled, to avoid async racing conditions.
        new Handler().postDelayed(() -> {
            try {
                if (player == null) {
                    // Initialize core configuration and listeners
                    initializePlayerCore(self);
                }
                if (playerNeedsSource && srcUri != null) {
                    exoPlayerView.invalidateAspectRatio();
                    // DRM session manager creation must be done on a different thread to prevent crashes so we start a new thread
                    ExecutorService es = Executors.newSingleThreadExecutor();
                    es.execute(() -> {
                        // DRM initialization must run on a different thread
                        DrmSessionManager drmSessionManager = initializePlayerDrm(self);
                        if (drmSessionManager == null && self.drmUUID != null) {
                            // Failed to intialize DRM session manager - cannot continue
                            Log.e("ExoPlayer Exception", "Failed to initialize DRM Session Manager Framework!");
                            eventEmitter.error("Failed to initialize DRM Session Manager Framework!", new Exception("DRM Session Manager Framework failure!"), "3003");
                            return;
                        }

                        // Initialize handler to run on the main thread
                        activity.runOnUiThread(() -> {
                            try {
                                // Source initialization must run on the main thread
                                initializePlayerSource(self, drmSessionManager);
                            } catch (Exception ex) {
                                self.playerNeedsSource = true;
                                Log.e("ExoPlayer Exception", "Failed to initialize Player!");
                                Log.e("ExoPlayer Exception", ex.toString());
                                self.eventEmitter.error(ex.toString(), ex, "1001");
                            }
                        });
                    });
                } else if (srcUri != null) {
                    initializePlayerSource(self, null);
                }
            } catch (Exception ex) {
                self.playerNeedsSource = true;
                Log.e("ExoPlayer Exception", "Failed to initialize Player!");
                Log.e("ExoPlayer Exception", ex.toString());
                eventEmitter.error(ex.toString(), ex, "1001");
            }
        }, 1);

    }

    private void initializePlayerCore(ReactExoplayerView self) {
        self.trackSelector = new DefaultTrackSelector(self.themedReactContext);
        self.trackSelector.setParameters(self.trackSelector.buildUponParameters()
                .setMaxVideoBitrate(maxBitRate == 0 ? Integer.MAX_VALUE : maxBitRate));

        DefaultAllocator allocator = new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
        RNVLoadControl loadControl = new RNVLoadControl(
                allocator,
                minBufferMs * 2,
                maxBufferMs * 2,
                bufferForPlaybackMs * 2,
                bufferForPlaybackAfterRebufferMs * 2,
                -1,
                true,
                backBufferDurationMs,
                DefaultLoadControl.DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME
        );
        DefaultRenderersFactory renderersFactory =
                new DefaultRenderersFactory(getContext())
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                        .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(getContext(), renderersFactory)
                .setTrackSelector(self.trackSelector)
                .setBandwidthMeter(bandwidthMeter)
                .setLoadControl(loadControl)
                .build();
        player.addListener(self);
        exoPlayerView.setPlayer(player);
        audioBecomingNoisyReceiver.setListener(self);
        bandwidthMeter.addEventListener(new Handler(), self);
        setPlayWhenReady(!isPaused);
        playerNeedsSource = true;

        PlaybackParameters params = new PlaybackParameters(rate, 1f);
        player.setPlaybackParameters(params);
    }

    private DrmSessionManager initializePlayerDrm(ReactExoplayerView self) {
        DrmSessionManager drmSessionManager = null;
        if (self.drmUUID != null) {
            try {
                drmSessionManager = self.buildDrmSessionManager(self.drmUUID, self.drmLicenseUrl,
                        self.drmLicenseHeader);
            } catch (UnsupportedDrmException e) {
                int errorStringId = Util.SDK_INT < 18 ? R.string.error_drm_not_supported
                        : (e.reason == UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME
                        ? R.string.error_drm_unsupported_scheme : R.string.error_drm_unknown);
                eventEmitter.error(getResources().getString(errorStringId), e, "3003");
                return null;
            }
        }
        return drmSessionManager;
    }

    private void initializePlayerSource(ReactExoplayerView self, DrmSessionManager drmSessionManager) {
        ArrayList<MediaSource> mediaSourceList = buildTextSources();
        MediaSource videoSource = buildMediaSource(self.srcUri, self.extension, drmSessionManager);
        MediaSource mediaSource;
        if (mediaSourceList.isEmpty()) {
            mediaSource = videoSource;
        } else {
            mediaSourceList.add(0, videoSource);
            MediaSource[] textSourceArray = mediaSourceList.toArray(
                    new MediaSource[0]
            );
            mediaSource = new MergingMediaSource(textSourceArray);
        }

        // wait for player to be set
        while (player == null) {
            try {
                wait();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                Log.e("ExoPlayer Exception", ex.toString());
            }
        }

        boolean haveResumePosition = resumeWindow != C.INDEX_UNSET;
        if (haveResumePosition) {
            player.seekTo(resumeWindow, resumePosition);
        }
        player.prepare(mediaSource, !haveResumePosition, false);
        playerNeedsSource = false;

        reLayout(exoPlayerView);
        eventEmitter.loadStart();
        loadVideoStarted = true;

        finishPlayerInitialization();
    }

    private void finishPlayerInitialization() {
        // Initializing the playerControlView
        initializePlayerControl();
        setControls(controls);
        applyModifiers();
        startBufferCheckTimer();
    }

    private DrmSessionManager buildDrmSessionManager(UUID uuid, String licenseUrl, String[] keyRequestPropertiesArray) throws UnsupportedDrmException {
        return buildDrmSessionManager(uuid, licenseUrl, keyRequestPropertiesArray, 0);
    }

    private DrmSessionManager buildDrmSessionManager(UUID uuid, String licenseUrl, String[] keyRequestPropertiesArray, int retryCount) {
        if (Util.SDK_INT < 18) {
            return null;
        }
        try {
            HttpMediaDrmCallback drmCallback = new HttpMediaDrmCallback(licenseUrl,
                    buildHttpDataSourceFactory());
            if (keyRequestPropertiesArray != null) {
                for (int i = 0; i < keyRequestPropertiesArray.length - 1; i += 2) {
                    drmCallback.setKeyRequestProperty(keyRequestPropertiesArray[i], keyRequestPropertiesArray[i + 1]);
                }
            }
            return new DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(uuid, uuid1 -> {
                        try {
                            return FrameworkMediaDrm.newInstance(uuid1);
                        } catch (UnsupportedDrmException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .setMultiSession(false)
                    .build(drmCallback);
        } catch (Exception ex) {
            if (retryCount < 3) {
                return buildDrmSessionManager(uuid, licenseUrl, keyRequestPropertiesArray, ++retryCount);
            }
            eventEmitter.error(ex.toString(), ex, "3006");
            return null;
        }
    }

    private MediaSource buildMediaSource(Uri uri, String overrideExtension, DrmSessionManager drmSessionManager) {
        if (uri == null) {
            throw new IllegalStateException("Invalid video uri");
        }

        int type = Util.inferContentType(!TextUtils.isEmpty(overrideExtension) ? "." + overrideExtension : uri.getLastPathSegment());
        config.setDisableDisconnectError(this.disableDisconnectError);
        MediaItem mediaItem = new MediaItem.Builder().setUri(uri).build();

        DrmSessionManagerProvider drmProvider = mediaItem1 -> drmSessionManager != null ? drmSessionManager : DrmSessionManager.DRM_UNSUPPORTED;

        switch (type) {
            case C.CONTENT_TYPE_SS:
                return new SsMediaSource.Factory(
                        new DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                        buildDataSourceFactory(false)
                ).setDrmSessionManagerProvider(drmProvider)
                        .setLoadErrorHandlingPolicy(config.buildLoadErrorHandlingPolicy(minLoadRetryCount))
                        .createMediaSource(mediaItem);
            case C.CONTENT_TYPE_DASH:
                return new DashMediaSource.Factory(
                        new DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                        buildDataSourceFactory(false)
                ).setDrmSessionManagerProvider(drmProvider)
                        .setLoadErrorHandlingPolicy(config.buildLoadErrorHandlingPolicy(minLoadRetryCount))
                        .createMediaSource(mediaItem);
            case C.CONTENT_TYPE_HLS:
                return new HlsMediaSource.Factory(mediaDataSourceFactory)
                        .setDrmSessionManagerProvider(drmProvider)
                        .setLoadErrorHandlingPolicy(config.buildLoadErrorHandlingPolicy(minLoadRetryCount))
                        .createMediaSource(mediaItem);
            case C.CONTENT_TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(mediaDataSourceFactory)
                        .setDrmSessionManagerProvider(drmProvider)
                        .setLoadErrorHandlingPolicy(config.buildLoadErrorHandlingPolicy(minLoadRetryCount))
                        .createMediaSource(mediaItem);
            default:
                throw new IllegalStateException("Unsupported type: " + type);
        }
    }

    private ArrayList<MediaSource> buildTextSources() {
        ArrayList<MediaSource> textSources = new ArrayList<>();
        if (textTracks == null) {
            return textSources;
        }

        for (int i = 0; i < textTracks.size(); ++i) {
            ReadableMap textTrack = textTracks.getMap(i);
            String language = textTrack.getString("language");
            String title = textTrack.hasKey("title")
                    ? textTrack.getString("title") : language + " " + i;
            Uri uri = Uri.parse(textTrack.getString("uri"));
            MediaSource textSource = buildTextSource(title, uri, textTrack.getString("type"),
                    language);
            textSources.add(textSource);
        }
        return textSources;
    }

    private MediaSource buildTextSource(String title, Uri uri, String mimeType, String language) {
        MediaItem.SubtitleConfiguration subtitleConfiguration = new MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(mimeType)
                .setLanguage(language)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .setLabel(title)
                .build();
        return new SingleSampleMediaSource.Factory(mediaDataSourceFactory)
                .createMediaSource(subtitleConfiguration, C.TIME_UNSET);
    }

    private void releasePlayer() {
        if (player != null) {
            updateResumePosition();
            player.release();
            player.removeListener(this);
            trackSelector = null;
            player = null;
        }
        progressHandler.removeMessages(SHOW_PROGRESS);
        themedReactContext.removeLifecycleEventListener(this);
        audioBecomingNoisyReceiver.removeListener();
        bandwidthMeter.removeEventListener(this);
    }

    private boolean requestAudioFocus() {
        if (disableFocus || srcUri == null || this.hasAudioFocus) {
            return true;
        }
        int result = audioManager.requestAudioFocus(this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void setPlayWhenReady(boolean playWhenReady) {
        if (player == null) {
            return;
        }

        if (playWhenReady) {
            this.hasAudioFocus = requestAudioFocus();
            if (this.hasAudioFocus) {
                player.setPlayWhenReady(true);
            }
        } else {
            player.setPlayWhenReady(false);
        }
    }

    private void startPlayback() {
        if (player != null) {
            switch (player.getPlaybackState()) {
                case Player.STATE_IDLE:
                case Player.STATE_ENDED:
                    initializePlayer();
                    break;
                case Player.STATE_BUFFERING:
                case Player.STATE_READY:
                    if (!player.getPlayWhenReady()) {
                        setPlayWhenReady(true);
                    }
                    break;
                default:
                    break;
            }
        } else {
            initializePlayer();
        }
        if (!disableFocus) {
            setKeepScreenOn(preventsDisplaySleepDuringVideoPlayback);
        }
    }

    private void pausePlayback() {
        if (player != null) {
            if (player.getPlayWhenReady()) {
                setPlayWhenReady(false);
            }
        }
        setKeepScreenOn(false);
    }

    private void stopPlayback() {
        onStopPlayback();
        releasePlayer();
    }

    private void onStopPlayback() {
        // if (isFullscreen) {
        //     setFullscreen(false);
        // }
        audioManager.abandonAudioFocus(this);
    }

    private void updateResumePosition() {
        resumeWindow = player.getCurrentMediaItemIndex();
        resumePosition = player.isCurrentMediaItemSeekable() ? Math.max(0, player.getCurrentPosition())
                : C.TIME_UNSET;
    }

    private void clearResumePosition() {
        resumeWindow = C.INDEX_UNSET;
        resumePosition = C.TIME_UNSET;
    }

    /**
     * Returns a new DataSource factory.
     *
     * @param useBandwidthMeter Whether to set {@link #bandwidthMeter} as a listener to the new
     *                          DataSource factory.
     * @return A new DataSource factory.
     */
    private DataSource.Factory buildDataSourceFactory(boolean useBandwidthMeter) {
        return DataSourceUtil.getDefaultDataSourceFactory(this.themedReactContext,
                useBandwidthMeter ? bandwidthMeter : null, requestHeaders);
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @return A new HttpDataSource factory.
     */
    private HttpDataSource.Factory buildHttpDataSourceFactory() {
        return DataSourceUtil.getDefaultHttpDataSourceFactory(this.themedReactContext, null, requestHeaders);
    }


    // AudioManager.OnAudioFocusChangeListener implementation

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                this.hasAudioFocus = false;
                eventEmitter.audioFocusChanged(false);
                pausePlayback();
                audioManager.abandonAudioFocus(this);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                eventEmitter.audioFocusChanged(false);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                this.hasAudioFocus = true;
                eventEmitter.audioFocusChanged(true);
                break;
            default:
                break;
        }

        if (player != null) {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                // Lower the volume
                if (!muted) {
                    player.setVolume(audioVolume * 0.8f);
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                // Raise it back to normal
                if (!muted) {
                    player.setVolume(audioVolume * 1);
                }
            }
        }
    }

    // AudioBecomingNoisyListener implementation

    @Override
    public void onAudioBecomingNoisy() {
        eventEmitter.audioBecomingNoisy();
    }

    // Player.Listener implementation

    @Override
    public void onIsLoadingChanged(boolean isLoading) {
        // Do nothing.
    }

    @Override
    public void onEvents(@NonNull Player player, Player.Events events) {
        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
            int playbackState = player.getPlaybackState();
            boolean playWhenReady = player.getPlayWhenReady();
            eventEmitter.playbackRateChange(playWhenReady && playbackState == ExoPlayer.STATE_READY ? 1.0f : 0.0f);
            switch (playbackState) {
                case Player.STATE_IDLE:
                    eventEmitter.idle();
                    clearProgressMessageHandler();
                    if (!player.getPlayWhenReady()) {
                        setKeepScreenOn(false);
                    }
                    break;
                case Player.STATE_BUFFERING:
                    onBuffering(true);
                    clearProgressMessageHandler();
                    setKeepScreenOn(preventsDisplaySleepDuringVideoPlayback);
                    break;
                case Player.STATE_READY:
                    eventEmitter.ready();
                    onBuffering(false);
                    startProgressHandler();
                    videoLoaded();
                    if (selectTrackWhenReady && isUsingContentResolution) {
                        selectTrackWhenReady = false;
                        setSelectedTrack(C.TRACK_TYPE_VIDEO, videoTrackType, videoTrackValue);
                    }
                    // Setting the visibility for the playerControlView
                    if (playerControlView != null) {
                        playerControlView.show();
                    }
                    setKeepScreenOn(preventsDisplaySleepDuringVideoPlayback);
                    break;
                case Player.STATE_ENDED:
                    eventEmitter.end();
                    onStopPlayback();
                    setKeepScreenOn(false);
                    break;
                default:
                    break;
            }
        }
    }

    private void startProgressHandler() {
        progressHandler.sendEmptyMessage(SHOW_PROGRESS);
    }

    /*
        The progress message handler will duplicate recursions of the onProgressMessage handler
        on change of player state from any state to STATE_READY with playWhenReady is true (when
        the video is not paused). This clears all existing messages.
     */
    private void clearProgressMessageHandler() {
        progressHandler.removeMessages(SHOW_PROGRESS);
    }

    private void videoLoaded() {
        if (loadVideoStarted) {
            loadVideoStarted = false;
            setSelectedAudioTrack(audioTrackType, audioTrackValue);
            setSelectedVideoTrack(videoTrackType, videoTrackValue);
            setSelectedTextTrack(textTrackType, textTrackValue);
            Format videoFormat = player.getVideoFormat();
            int width = videoFormat != null ? videoFormat.width : 0;
            int height = videoFormat != null ? videoFormat.height : 0;
            String trackId = videoFormat != null ? videoFormat.id : "-1";

            // Properties that must be accessed on the main thread
            long duration = player.getDuration();
            long currentPosition = player.getCurrentPosition();
            WritableArray audioTrackInfo = getAudioTrackInfo();
            WritableArray textTrackInfo = getTextTrackInfo();
            int trackRendererIndex = getTrackRendererIndex(C.TRACK_TYPE_VIDEO);

            ExecutorService es = Executors.newSingleThreadExecutor();
            es.execute(() -> {
                // To prevent ANRs caused by getVideoTrackInfo we run this on a different thread and notify the player only when we're done
                eventEmitter.load(duration, currentPosition, width, height,
                        audioTrackInfo, textTrackInfo, getVideoTrackInfo(trackRendererIndex), trackId);
            });
        }
    }

    private WritableArray getAudioTrackInfo() {
        WritableArray audioTracks = Arguments.createArray();

        if (trackSelector == null) {
            // Likely player is unmounting so no audio tracks are available anymore
            return audioTracks;
        }

        MappingTrackSelector.MappedTrackInfo info = trackSelector.getCurrentMappedTrackInfo();
        int index = getTrackRendererIndex(C.TRACK_TYPE_AUDIO);
        if (info == null || index == C.INDEX_UNSET) {
            return audioTracks;
        }

        TrackGroupArray groups = info.getTrackGroups(index);
        for (int i = 0; i < groups.length; ++i) {
            Format format = groups.get(i).getFormat(0);
            WritableMap audioTrack = Arguments.createMap();
            audioTrack.putInt("index", i);
            audioTrack.putString("title", format.id != null ? format.id : "");
            audioTrack.putString("type", format.sampleMimeType);
            audioTrack.putString("language", format.language != null ? format.language : "");
            audioTrack.putString("bitrate", format.bitrate == Format.NO_VALUE ? ""
                    : String.format(Locale.US, "%.2fMbps", format.bitrate / 1000000f));
            audioTracks.pushMap(audioTrack);
        }
        return audioTracks;
    }
    private WritableArray getVideoTrackInfo(int trackRendererIndex) {

        if (this.contentStartTime != -1L) {
            WritableArray contentVideoTracks = this.getVideoTrackInfoFromManifest();
            if (contentVideoTracks != null) {
                isUsingContentResolution = true;
                return contentVideoTracks;
            }
        }

        WritableArray videoTracks = Arguments.createArray();

        MappingTrackSelector.MappedTrackInfo info = trackSelector.getCurrentMappedTrackInfo();

        if (info == null || trackRendererIndex == C.INDEX_UNSET) {
            return videoTracks;
        }

        TrackGroupArray groups = info.getTrackGroups(trackRendererIndex);
        for (int i = 0; i < groups.length; ++i) {
            TrackGroup group = groups.get(i);

            for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                Format format = group.getFormat(trackIndex);
                if (isFormatSupported(format)) {
                    WritableMap videoTrack = Arguments.createMap();
                    videoTrack.putInt("width", format.width == Format.NO_VALUE ? 0 : format.width);
                    videoTrack.putInt("height",format.height == Format.NO_VALUE ? 0 : format.height);
                    videoTrack.putInt("bitrate", format.bitrate == Format.NO_VALUE ? 0 : format.bitrate);
                    videoTrack.putString("codecs", format.codecs != null ? format.codecs : "");
                    videoTrack.putString("trackId", format.id == null ? String.valueOf(trackIndex) : format.id);
                    videoTracks.pushMap(videoTrack);
                }
            }
        }

        return videoTracks;
    }

    private WritableArray getVideoTrackInfoFromManifest() {
        return this.getVideoTrackInfoFromManifest(0);
    }

    // We need retry count to in case where minefest request fails from poor network conditions
    private WritableArray getVideoTrackInfoFromManifest(int retryCount) {
        ExecutorService es = Executors.newSingleThreadExecutor();
        final DataSource dataSource = this.mediaDataSourceFactory.createDataSource();
        final Uri sourceUri = this.srcUri;
        final long startTime = this.contentStartTime * 1000 - 100; // s -> ms with 100ms offset

        Future<WritableArray> result = es.submit(new Callable<>() {
            final DataSource ds = dataSource;
            final Uri uri = sourceUri;
            final long startTimeUs = startTime * 1000; // ms -> us

            public WritableArray call() {
                WritableArray videoTracks = Arguments.createArray();
                try {
                    DashManifest manifest = DashUtil.loadManifest(this.ds, this.uri);
                    int periodCount = manifest.getPeriodCount();
                    for (int i = 0; i < periodCount; i++) {
                        Period period = manifest.getPeriod(i);
                        for (int adaptationIndex = 0; adaptationIndex < period.adaptationSets.size(); adaptationIndex++) {
                            AdaptationSet adaptation = period.adaptationSets.get(adaptationIndex);
                            if (adaptation.type != C.TRACK_TYPE_VIDEO) {
                                continue;
                            }
                            boolean hasFoundContentPeriod = false;
                            for (int representationIndex = 0; representationIndex < adaptation.representations.size(); representationIndex++) {
                                Representation representation = adaptation.representations.get(representationIndex);
                                Format format = representation.format;
                                if (representation.presentationTimeOffsetUs <= startTimeUs) {
                                    break;
                                }
                                hasFoundContentPeriod = true;
                                WritableMap videoTrack = Arguments.createMap();
                                videoTrack.putInt("width", format.width == Format.NO_VALUE ? 0 : format.width);
                                videoTrack.putInt("height", format.height == Format.NO_VALUE ? 0 : format.height);
                                videoTrack.putInt("bitrate", format.bitrate == Format.NO_VALUE ? 0 : format.bitrate);
                                videoTrack.putString("codecs", format.codecs != null ? format.codecs : "");
                                videoTrack.putString("trackId",
                                        format.id == null ? String.valueOf(representationIndex) : format.id);
                                if (isFormatSupported(format)) {
                                    videoTracks.pushMap(videoTrack);
                                }
                            }
                            if (hasFoundContentPeriod) {
                                return videoTracks;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                return null;
            }
        });

        try {
            WritableArray results = result.get(3000, TimeUnit.MILLISECONDS);
            if (results == null && retryCount < 1) {
                return this.getVideoTrackInfoFromManifest(++retryCount);
            }
            es.shutdown();
            return results;
        } catch (Exception ignored) {}

        return null;
    }

    private WritableArray getTextTrackInfo() {
        WritableArray textTracks = Arguments.createArray();

        MappingTrackSelector.MappedTrackInfo info = trackSelector.getCurrentMappedTrackInfo();
        int index = getTrackRendererIndex(C.TRACK_TYPE_TEXT);
        if (info == null || index == C.INDEX_UNSET) {
            return textTracks;
        }

        TrackGroupArray groups = info.getTrackGroups(index);
        for (int i = 0; i < groups.length; ++i) {
            Format format = groups.get(i).getFormat(0);
            WritableMap textTrack = Arguments.createMap();
            textTrack.putInt("index", i);
            textTrack.putString("title", format.id != null ? format.id : "");
            textTrack.putString("type", format.sampleMimeType);
            textTrack.putString("language", format.language != null ? format.language : "");
            textTracks.pushMap(textTrack);
        }
        return textTracks;
    }

    private void onBuffering(boolean buffering) {
        if (isBuffering == buffering) {
            return;
        }

        isBuffering = buffering;
        eventEmitter.buffering(buffering);
    }

    @Override
    public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
        if (playerNeedsSource) {
            // This will only occur if the user has performed a seek whilst in the error state. Update the
            // resume position so that if the user then retries, playback will resume from the position to
            // which they seeked.
            updateResumePosition();
        }
        if (isUsingContentResolution) {
            // Discontinuity events might have a different track list so we update the selected track
            setSelectedTrack(C.TRACK_TYPE_VIDEO, videoTrackType, videoTrackValue);
            selectTrackWhenReady = true;
        }
        // When repeat is turned on, reaching the end of the video will not cause a state change
        // so we need to explicitly detect it.
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                && player.getRepeatMode() == Player.REPEAT_MODE_ONE) {
            eventEmitter.end();
        }

    }

    @Override
    public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
        // Do nothing.
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (playbackState == Player.STATE_READY && seekTime != C.TIME_UNSET) {
            eventEmitter.seek(player.getCurrentPosition(), seekTime);
            seekTime = C.TIME_UNSET;
            if (isUsingContentResolution) {
                // We need to update the selected track to make sure that it still matches user selection if track list has changed in this period
                setSelectedTrack(C.TRACK_TYPE_VIDEO, videoTrackType, videoTrackValue);
            }
        }
    }

    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
        // Do nothing.
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        // Do nothing.
    }

//    @Override
//    public void onTracksInfoChanged()
//        // Do nothing.
//    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters params) {
        eventEmitter.playbackRateChange(params.speed);
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        eventEmitter.playbackStateChanged(isPlaying);
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException e) {
        String errorString = "ExoPlaybackException: " + PlaybackException.getErrorCodeName(e.errorCode);
        String errorCode = "2" + e.errorCode;
        switch(e.errorCode) {
            case PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED:
            case PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED:
            case PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED:
            case PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR:
            case PlaybackException.ERROR_CODE_DRM_UNSPECIFIED:
                if (!hasDrmFailed) {
                    // When DRM fails to reach the app level certificate server it will fail with a source error so we assume that it is DRM related and try one more time
                    hasDrmFailed = true;
                    playerNeedsSource = true;
                    updateResumePosition();
                    initializePlayer();
                    setPlayWhenReady(true);
                    return;
                }
                break;
            default:
                break;
        }
        eventEmitter.error(errorString, e, errorCode);
        playerNeedsSource = true;
        if (isBehindLiveWindow(e)) {
            clearResumePosition();
            initializePlayer();
        } else {
            updateResumePosition();
        }
    }

    private static boolean isBehindLiveWindow(PlaybackException e) {
        return e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW;
    }

    public int getTrackRendererIndex(int trackType) {
        if (player != null) {
            int rendererCount = player.getRendererCount();
            for (int rendererIndex = 0; rendererIndex < rendererCount; rendererIndex++) {
                if (player.getRendererType(rendererIndex) == trackType) {
                    return rendererIndex;
                }
            }
        }
        return C.INDEX_UNSET;
    }

    @Override
    public void onMetadata(Metadata metadata) {
        eventEmitter.timedMetadata(metadata);
    }

    // ReactExoplayerViewManager public api

    public void setSrc(final Uri uri, final String extension, Map<String, String> headers) {
        if (uri != null) {
            boolean isSourceEqual = uri.equals(srcUri);
            hasDrmFailed = false;
            this.srcUri = uri;
            this.extension = extension;
            this.requestHeaders = headers;
            this.mediaDataSourceFactory =
                    DataSourceUtil.getDefaultDataSourceFactory(this.themedReactContext, bandwidthMeter,
                            this.requestHeaders);

            if (!isSourceEqual) {
                reloadSource();
            }
        }
    }

    public void clearSrc() {
        if (srcUri != null) {
            player.stop();
            player.clearMediaItems();
            this.srcUri = null;
            this.extension = null;
            this.requestHeaders = null;
            this.mediaDataSourceFactory = null;
            clearResumePosition();
        }
    }

    public void setProgressUpdateInterval(final float progressUpdateInterval) {
        mProgressUpdateInterval = progressUpdateInterval;
    }

    public void setReportBandwidth(boolean reportBandwidth) {
        mReportBandwidth = reportBandwidth;
    }

    public void setRawSrc(final Uri uri, final String extension) {
        if (uri != null) {
            boolean isSourceEqual = uri.equals(srcUri);
            this.srcUri = uri;
            this.extension = extension;
            this.mediaDataSourceFactory = buildDataSourceFactory(true);

            if (!isSourceEqual) {
                reloadSource();
            }
        }
    }

    public void setTextTracks(ReadableArray textTracks) {
        this.textTracks = textTracks;
        reloadSource();
    }

    private void reloadSource() {
        playerNeedsSource = true;
        initializePlayer();
    }

    public void setResizeModeModifier(@ResizeMode.Mode int resizeMode) {
        exoPlayerView.setResizeMode(resizeMode);
    }

    private void applyModifiers() {
        setRepeatModifier(repeat);
        setMutedModifier(muted);
    }

    public void setRepeatModifier(boolean repeat) {
        if (player != null) {
            if (repeat) {
                player.setRepeatMode(Player.REPEAT_MODE_ONE);
            } else {
                player.setRepeatMode(Player.REPEAT_MODE_OFF);
            }
        }
        this.repeat = repeat;
    }

    public void setPreventsDisplaySleepDuringVideoPlayback(boolean preventsDisplaySleepDuringVideoPlayback) {
        this.preventsDisplaySleepDuringVideoPlayback = preventsDisplaySleepDuringVideoPlayback;
    }

    public void setSelectedTrack(int trackType, String type, Dynamic value) {
        if (player == null || trackSelector == null) return;

        // Handle null type
        if (type == null) {
            type = "default";
        }

        DefaultTrackSelector.Parameters.Builder parametersBuilder = trackSelector.getParameters().buildUpon();

        switch (type) {
            case "disabled":
                parametersBuilder.setRendererDisabled(trackType, true);
                break;
            case "language":
                if (trackType == C.TRACK_TYPE_AUDIO) {
                    parametersBuilder.setPreferredAudioLanguage(value != null ? value.asString() : null);
                } else if (trackType == C.TRACK_TYPE_TEXT) {
                    parametersBuilder.setPreferredTextLanguage(value != null ? value.asString() : null);
                }
                break;
            case "index":
            case "resolution":
                int rendererIndex = getTrackRendererIndex(trackType);
                if (rendererIndex == C.INDEX_UNSET) {
                    return;
                }

                MappingTrackSelector.MappedTrackInfo trackInfo = trackSelector.getCurrentMappedTrackInfo();
                if (trackInfo == null) {
                    return;
                }

                TrackGroupArray trackGroups = trackInfo.getTrackGroups(rendererIndex);
                int[] trackIndices = new int[1];
                int groupIndex = C.INDEX_UNSET;

                for (int i = 0; i < trackGroups.length; i++) {
                    TrackGroup group = trackGroups.get(i);
                    for (int j = 0; j < group.length; j++) {
                        if (type.equals("index") && value != null && j == value.asInt()) {
                            groupIndex = i;
                            trackIndices[0] = j;
                            break;
                        } else if (type.equals("resolution") && trackType == C.TRACK_TYPE_VIDEO && value != null) {
                            Format format = group.getFormat(j);
                            if (format.height <= value.asInt()) {
                                groupIndex = i;
                                trackIndices[0] = j;
                                break;
                            }
                        }
                    }
                    if (groupIndex != C.INDEX_UNSET) break;
                }

                if (groupIndex != C.INDEX_UNSET) {
                    parametersBuilder.setSelectionOverride(rendererIndex, trackGroups,
                            new DefaultTrackSelector.SelectionOverride(groupIndex, trackIndices));
                } else {
                    parametersBuilder.clearSelectionOverrides(rendererIndex);
                }
                break;
            default:
                // For "default" or any other type, we don't make any specific selection
                break;
        }

        trackSelector.setParameters(parametersBuilder.build());
    }

    private boolean isFormatSupported(Format format) {
        int width = format.width == Format.NO_VALUE ? 0 : format.width;
        int height = format.height == Format.NO_VALUE ? 0 : format.height;
        float frameRate = format.frameRate == Format.NO_VALUE ? 0 : format.frameRate;
        String mimeType = format.sampleMimeType;
        if (mimeType == null) {
            return true;
        }
        boolean isSupported;
        try {
            MediaCodecInfo codecInfo = MediaCodecUtil.getDecoderInfo(mimeType, false, false);
            assert codecInfo != null;
            isSupported = codecInfo.isVideoSizeAndRateSupportedV21(width, height, frameRate);
        } catch (Exception e) {
            // Failed to get decoder info - assume it is supported
            isSupported = true;
        }
        return isSupported;
    }

    private int getGroupIndexForDefaultLocale(@NonNull TrackGroupArray groups) {
        if (groups.length == 0){
            return C.INDEX_UNSET;
        }

        int groupIndex = 0; // default if no match
        String locale2 = Locale.getDefault().getLanguage(); // 2 letter code
        String locale3 = Locale.getDefault().getISO3Language(); // 3 letter code
        for (int i = 0; i < groups.length; ++i) {
            Format format = groups.get(i).getFormat(0);
            String language = format.language;
            if (language != null && (language.equals(locale2) || language.equals(locale3))) {
                groupIndex = i;
                break;
            }
        }
        return groupIndex;
    }

    public void setSelectedVideoTrack(String type, Dynamic value) {
        videoTrackType = type;
        videoTrackValue = value;
        setSelectedTrack(C.TRACK_TYPE_VIDEO, videoTrackType, videoTrackValue);
    }

    public void setSelectedAudioTrack(String type, Dynamic value) {
        audioTrackType = type;
        audioTrackValue = value;
        setSelectedTrack(C.TRACK_TYPE_AUDIO, audioTrackType, audioTrackValue);
    }

    public void setSelectedTextTrack(String type, Dynamic value) {
        textTrackType = type;
        textTrackValue = value;
        setSelectedTrack(C.TRACK_TYPE_TEXT, textTrackType, textTrackValue);
    }

    public void setPausedModifier(boolean paused) {
        isPaused = paused;
        if (player != null) {
            if (!paused) {
                startPlayback();
            } else {
                pausePlayback();
            }
        }
    }

    public void setMutedModifier(boolean muted) {
        this.muted = muted;
        if (player != null) {
            player.setVolume(muted ? 0.f : audioVolume);
        }
    }

    public void setVolumeModifier(float volume) {
        audioVolume = volume;
        if (player != null) {
            player.setVolume(audioVolume);
        }
    }

    public void seekTo(long positionMs) {
        if (player != null) {
            player.seekTo(positionMs);
            eventEmitter.seek(player.getCurrentPosition(), positionMs);
        }
    }

    public void setRateModifier(float newRate) {
        rate = newRate;

        if (player != null) {
            PlaybackParameters params = new PlaybackParameters(rate, 1f);
            player.setPlaybackParameters(params);
        }
    }

    public void setMaxBitRateModifier(int newMaxBitRate) {
        maxBitRate = newMaxBitRate;
        if (player != null) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setMaxVideoBitrate(maxBitRate == 0 ? Integer.MAX_VALUE : maxBitRate));
        }
    }

    public void setMinLoadRetryCountModifier(int newMinLoadRetryCount) {
        minLoadRetryCount = newMinLoadRetryCount;
        releasePlayer();
        initializePlayer();
    }

    public void setPlayInBackground(boolean playInBackground) {
        this.playInBackground = playInBackground;
    }

    public void setDisableFocus(boolean disableFocus) {
        this.disableFocus = disableFocus;
    }

    public void setBackBufferDurationMs(int backBufferDurationMs) {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long freeMemory = runtime.maxMemory() - usedMemory;
        long reserveMemory = (long)minBackBufferMemoryReservePercent * runtime.maxMemory();
        if (reserveMemory > freeMemory) {
            // We don't have enough memory in reserve so we will
            Log.w("ExoPlayer Warning", "Not enough reserve memory, setting back buffer to 0ms to reduce memory pressure!");
            this.backBufferDurationMs = 0;
            return;
        }
        this.backBufferDurationMs = backBufferDurationMs;
    }

    public void setContentStartTime(int contentStartTime) {
        this.contentStartTime = contentStartTime;
    }

    public void setDisableBuffering(boolean disableBuffering) {
        this.disableBuffering = disableBuffering;
    }

    public void setDisableDisconnectError(boolean disableDisconnectError) {
        this.disableDisconnectError = disableDisconnectError;
    }

    public void setFullscreen(boolean fullscreen) {
        if (fullscreen == isFullscreen) {
            return; // Avoid generating events when nothing is changing
        }
        isFullscreen = fullscreen;

        // Activity activity = themedReactContext.getCurrentActivity();
        // if (activity == null) {
        //     return;
        // }
        // Window window = activity.getWindow();
        // View decorView = window.getDecorView();
        // int uiOptions;
        if (isFullscreen) {
            // if (Util.SDK_INT >= 19) { // 4.4+
            //     uiOptions = SYSTEM_UI_FLAG_HIDE_NAVIGATION
            //             | SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            //             | SYSTEM_UI_FLAG_FULLSCREEN;
            // } else {
            //     uiOptions = SYSTEM_UI_FLAG_HIDE_NAVIGATION
            //             | SYSTEM_UI_FLAG_FULLSCREEN;
            // }
            eventEmitter.fullscreenWillPresent();
            // decorView.setSystemUiVisibility(uiOptions);
            showFullscreen();
            eventEmitter.fullscreenDidPresent();
        } else {
            // uiOptions = View.SYSTEM_UI_FLAG_VISIBLE;
            eventEmitter.fullscreenWillDismiss();
            // decorView.setSystemUiVisibility(uiOptions);
            eventEmitter.fullscreenDidDismiss();
            if (fullScreenDelegate != null) {
                fullScreenDelegate.closeFullScreen();
                exoPlayerView.setPlayer(player);
            }
        }
    }
    public void setFullscreenOrientation(String orientation) {
        this.fullScreenOrientation = orientation;
    }

    public void setUseTextureView(boolean useTextureView) {
        boolean finallyUseTextureView = useTextureView && this.drmUUID == null;
        exoPlayerView.setUseTextureView(finallyUseTextureView);
    }

    public void useSecureView(boolean useSecureView) {
        exoPlayerView.useSecureView(useSecureView);
    }

    public void setHideShutterView(boolean hideShutterView) {
        exoPlayerView.setHideShutterView(hideShutterView);
    }

    public void setBufferConfig(int newMinBufferMs, int newMaxBufferMs, int newBufferForPlaybackMs, int newBufferForPlaybackAfterRebufferMs, double newMaxHeapAllocationPercent, double newMinBackBufferMemoryReservePercent, double newMinBufferMemoryReservePercent) {
        minBufferMs = newMinBufferMs;
        maxBufferMs = newMaxBufferMs;
        bufferForPlaybackMs = newBufferForPlaybackMs;
        bufferForPlaybackAfterRebufferMs = newBufferForPlaybackAfterRebufferMs;
        maxHeapAllocationPercent = newMaxHeapAllocationPercent;
        minBackBufferMemoryReservePercent = newMinBackBufferMemoryReservePercent;
        minBufferMemoryReservePercent = newMinBufferMemoryReservePercent;
        releasePlayer();
        initializePlayer();
    }

    public void setDrmType(UUID drmType) {
        this.drmUUID = drmType;
    }

    public void setDrmLicenseUrl(String licenseUrl){
        this.drmLicenseUrl = licenseUrl;
    }

    public void setDrmLicenseHeader(String[] header){
        this.drmLicenseHeader = header;
    }


    @Override
    public void onDrmKeysLoaded(int windowIndex, MediaSource.MediaPeriodId mediaPeriodId) {
        Log.d("DRM Info", "onDrmKeysLoaded");
    }

    @Override
    public void onDrmSessionManagerError(int windowIndex, MediaSource.MediaPeriodId mediaPeriodId, Exception e) {
        Log.d("DRM Info", "onDrmSessionManagerError");
        eventEmitter.error("onDrmSessionManagerError", e, "3002");
    }

    @Override
    public void onDrmKeysRestored(int windowIndex, MediaSource.MediaPeriodId mediaPeriodId) {
        Log.d("DRM Info", "onDrmKeysRestored");
    }

    @Override
    public void onDrmKeysRemoved(int windowIndex, MediaSource.MediaPeriodId mediaPeriodId) {
        Log.d("DRM Info", "onDrmKeysRemoved");
    }

    /**
     * Handling controls prop
     *
     * @param controls  Controls prop, if true enable controls, if false disable them
     */
    public void setControls(boolean controls) {
        this.controls = controls;
        if (player == null || exoPlayerView == null) return;
        if (controls) {
            addPlayerControl();
        } else {
            int indexOfPC = indexOfChild(playerControlView);
            if (indexOfPC != -1) {
                removeViewAt(indexOfPC);
            }
        }
    }

    public interface FullScreenDelegate {
        void closeFullScreen();
    }
}
