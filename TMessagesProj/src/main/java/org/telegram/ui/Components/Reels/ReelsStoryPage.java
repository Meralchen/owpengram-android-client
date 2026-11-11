package org.telegram.ui.Components.Reels;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.RadialProgressView;

/** One Story-shaped page: full-bleed media, segmented progress, Story header and action row. */
public final class ReelsStoryPage extends FrameLayout {

    public interface Delegate {
        void onNext(ReelsStoryPage page);

        void onPrevious(ReelsStoryPage page);

        void onClose();

        void onLike(ReelsItem item);

        void onComments(ReelsItem item);

        void onShare(ReelsItem item);

        void onSave(ReelsItem item);

        void onOpenProfile(ReelsItem item);

        void onFollow(ReelsItem item);

        void onMore(ReelsItem item);

        void onEnded(ReelsStoryPage page);

        void onView(ReelsItem item, long positionMs, boolean completed);
    }

    private final TextureView textureView;
    private final ReelsCoverView coverView;
    private final View topScrim;
    private final View bottomScrim;
    private final ReelsStoryProgress progressView;
    private final ReelsStoryHeader header;
    private final ReelsStoryActions actions;
    private final RadialProgressView loadingView;
    private final ImageView playView;
    private final ImageView heartView;
    private final LinearLayout errorView;
    private final GestureDetector gestures;
    private final Delegate delegate;
    private final int account;

    private ReelsItem item;
    private ExoPlayer player;
    private boolean active;
    private boolean muted;
    private boolean own;
    private boolean viewStarted;
    private boolean viewCompleted;
    private boolean dragging;
    private boolean holding;
    private boolean wasPlayingBeforeHold;
    private float downY;
    private float downX;
    private int videoWidth;
    private int videoHeight;
    private int index;
    private int count = 1;
    private final int touchSlop;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY) {
                loadingView.setVisibility(GONE);
                applyVideoTransform();
            } else if (state == Player.STATE_BUFFERING) {
                loadingView.setVisibility(VISIBLE);
            } else if (state == Player.STATE_ENDED) {
                progressView.setProgress(1f);
                loadingView.setVisibility(GONE);
                playView.setVisibility(GONE);
                if (!viewCompleted) {
                    viewCompleted = true;
                    delegate.onView(item, player == null ? 0 : player.getDuration(), true);
                }
                delegate.onEnded(ReelsStoryPage.this);
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            playView.setVisibility(isPlaying ? GONE : VISIBLE);
            setKeepScreenOn(isPlaying);
            if (isPlaying && !viewStarted) {
                viewStarted = true;
                delegate.onView(item, player == null ? 0 : player.getCurrentPosition(), false);
                startTicker();
            }
            if (!isPlaying) {
                stopTicker();
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            loadingView.setVisibility(GONE);
            playView.setVisibility(GONE);
            errorView.setVisibility(VISIBLE);
            setKeepScreenOn(false);
        }

        @Override
        public void onRenderedFirstFrame() {
            coverView.showImage();
        }

        @Override
        public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
            videoWidth = videoSize.width;
            videoHeight = videoSize.height;
            applyVideoTransform();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!active || player == null) return;
            final long duration = player.getDuration();
            if (duration > 0) {
                final long position = player.getCurrentPosition();
                item.resumePositionMs = position;
                progressView.setProgress(position / (float) duration);
            }
            postDelayed(this, 50);
        }
    };

    public ReelsStoryPage(Context context, int account, Delegate delegate) {
        super(context);
        this.account = account;
        this.delegate = delegate;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBackgroundColor(ReelsTheme.STAGE_BACKGROUND);
        setClickable(true);

        textureView = new TextureView(context);
        addView(textureView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        coverView = new ReelsCoverView(context);
        addView(coverView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        topScrim = new View(context);
        final GradientDrawable topGradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x00000000});
        topScrim.setBackground(topGradient);
        addView(topScrim, new LayoutParams(LayoutParams.MATCH_PARENT, dp(170), Gravity.TOP));

        bottomScrim = new View(context);
        final GradientDrawable bottomGradient = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x00000000});
        bottomScrim.setBackground(bottomGradient);
        addView(bottomScrim, new LayoutParams(LayoutParams.MATCH_PARENT, dp(300), Gravity.BOTTOM));

        progressView = new ReelsStoryProgress(context);
        addView(progressView, new LayoutParams(LayoutParams.MATCH_PARENT, dp(12), Gravity.TOP));

        header = new ReelsStoryHeader(context, account, new ReelsStoryHeader.Delegate() {
            @Override
            public void onClose() {
                delegate.onClose();
            }

            @Override
            public void onMute() {
                setMuted(!muted);
            }

            @Override
            public void onFollow() {
                delegate.onFollow(item);
            }

            @Override
            public void onOpenProfile() {
                delegate.onOpenProfile(item);
            }

            @Override
            public void onMore() {
                delegate.onMore(item);
            }
        });
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP));

        actions = new ReelsStoryActions(context, new ReelsStoryActions.Delegate() {
            @Override
            public void onLike() {
                delegate.onLike(item);
            }

            @Override
            public void onComments() {
                delegate.onComments(item);
            }

            @Override
            public void onShare() {
                delegate.onShare(item);
            }

            @Override
            public void onSave() {
                delegate.onSave(item);
            }
        });
        addView(actions, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        loadingView = new RadialProgressView(context);
        final LayoutParams loadingParams = new LayoutParams(dp(42), dp(42), Gravity.CENTER);
        addView(loadingView, loadingParams);

        playView = new ImageView(context);
        playView.setImageResource(R.drawable.ic_action_play);
        playView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        playView.setPadding(dp(17), dp(17), dp(17), dp(17));
        playView.setBackground(ReelsTheme.rounded(0x66000000, dp(34)));
        playView.setContentDescription("Play Reel");
        playView.setOnClickListener(v -> togglePlayback());
        final LayoutParams playParams = new LayoutParams(dp(68), dp(68), Gravity.CENTER);
        addView(playView, playParams);

        errorView = new LinearLayout(context);
        errorView.setOrientation(LinearLayout.VERTICAL);
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(dp(24), dp(18), dp(24), dp(18));
        errorView.setBackground(ReelsTheme.rounded(0x99000000, dp(12)));
        final TextView errorText = new TextView(context);
        errorText.setText(LocaleController.getString(R.string.ReelsPlayError));
        errorText.setTextColor(Color.WHITE);
        errorText.setTextSize(14);
        errorText.setGravity(Gravity.CENTER);
        errorView.addView(errorText);
        errorView.setOnClickListener(v -> retry());
        addView(errorView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        heartView = new ImageView(context);
        heartView.setImageResource(R.drawable.media_like_active);
        heartView.setAlpha(0f);
        heartView.setScaleX(0.7f);
        heartView.setScaleY(0.7f);
        final LayoutParams heartParams = new LayoutParams(dp(104), dp(104), Gravity.CENTER);
        addView(heartView, heartParams);

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                final float x = e.getX();
                if (x < getWidth() / 3f) {
                    delegate.onPrevious(ReelsStoryPage.this);
                } else if (x > getWidth() * 2f / 3f) {
                    delegate.onNext(ReelsStoryPage.this);
                } else {
                    togglePlayback();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                delegate.onLike(item);
                animateHeart();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                holding = true;
                wasPlayingBeforeHold = player != null && player.isPlaying();
                if (player != null) player.pause();
                setChromeAlpha(0.35f);
            }
        });
        setOnTouchListener((v, event) -> handleTouch(event));
        setOnClickListener(v -> {
        });
    }

    public void bind(ReelsItem item, int index, int count, boolean own, boolean muted) {
        this.item = item;
        this.index = index;
        this.count = Math.max(1, count);
        this.own = own;
        this.muted = muted;
        viewStarted = false;
        viewCompleted = false;
        errorView.setVisibility(GONE);
        playView.setVisibility(VISIBLE);
        loadingView.setVisibility(VISIBLE);
        coverView.setCover(item.media, item.thumb, item.initial());
        header.bind(item, own, muted);
        actions.bind(item, own);
        progressView.setCount(this.count);
        progressView.setSelected(index);
    }

    public ReelsItem getItem() {
        return item;
    }

    public int getIndex() {
        return index;
    }

    public void setCount(int count) {
        this.count = Math.max(1, count);
        progressView.setCount(count);
    }

    public void update(ReelsItem updated) {
        if (updated == null || item == null) return;
        item.copyCountersFrom(updated);
        header.bind(item, own, muted);
        actions.update(item);
    }

    public void setFollowing(boolean following) {
        header.setFollowing(following);
    }

    public void setMuted(boolean value) {
        muted = value;
        if (player != null) {
            player.setVolume(value ? 0f : 1f);
        }
        header.setMuted(value);
    }

    public boolean isMuted() {
        return muted;
    }

    public void setInsets(int top, int bottom) {
        final LayoutParams headerParams = (LayoutParams) header.getLayoutParams();
        headerParams.topMargin = top + dp(22);
        header.setLayoutParams(headerParams);
        final LayoutParams progressParams = (LayoutParams) progressView.getLayoutParams();
        progressParams.topMargin = top + dp(4);
        progressView.setLayoutParams(progressParams);
        final LayoutParams actionsParams = (LayoutParams) actions.getLayoutParams();
        actionsParams.bottomMargin = bottom + dp(2);
        actions.setLayoutParams(actionsParams);
        final LayoutParams topScrimParams = (LayoutParams) topScrim.getLayoutParams();
        topScrimParams.height = top + dp(170);
        topScrim.setLayoutParams(topScrimParams);
        final LayoutParams bottomScrimParams = (LayoutParams) bottomScrim.getLayoutParams();
        bottomScrimParams.height = bottom + dp(300);
        bottomScrim.setLayoutParams(bottomScrimParams);
    }

    public void attachPlayer(ExoPlayer newPlayer, boolean play) {
        if (player == newPlayer) {
            if (play) resume();
            return;
        }
        if (player != null) {
            player.removeListener(playerListener);
            player.setVideoTextureView(null);
        }
        player = newPlayer;
        if (player != null) {
            player.addListener(playerListener);
            player.setVideoTextureView(textureView);
            player.setVolume(muted ? 0f : 1f);
            if (play) player.play();
        }
    }

    public void detachPlayer() {
        stopTicker();
        if (player != null) {
            if (item != null) item.resumePositionMs = Math.max(0, player.getCurrentPosition());
            player.pause();
            player.removeListener(playerListener);
            player.setVideoTextureView(null);
            player = null;
        }
        active = false;
        setKeepScreenOn(false);
    }

    public void setActive(boolean value) {
        active = value;
        if (value) {
            startTicker();
        } else {
            stopTicker();
            setKeepScreenOn(false);
        }
    }

    public void resume() {
        if (player != null) {
            errorView.setVisibility(GONE);
            player.play();
        }
    }

    public void pause() {
        if (player != null) player.pause();
    }

    public void togglePlayback() {
        if (player == null) {
            retry();
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            playView.setVisibility(VISIBLE);
        } else {
            errorView.setVisibility(GONE);
            player.play();
        }
    }

    public void retry() {
        if (player == null) return;
        errorView.setVisibility(GONE);
        loadingView.setVisibility(VISIBLE);
        player.prepare();
        player.play();
    }

    public void setChromeAlpha(float alpha) {
        header.setAlpha(alpha);
        actions.setAlpha(alpha);
        progressView.setAlpha(alpha);
    }

    private void animateHeart() {
        heartView.animate().cancel();
        heartView.setAlpha(1f);
        heartView.setScaleX(0.7f);
        heartView.setScaleY(0.7f);
        final AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(heartView, "scaleX", 0.7f, 1.15f),
                ObjectAnimator.ofFloat(heartView, "scaleY", 0.7f, 1.15f),
                ObjectAnimator.ofFloat(heartView, "alpha", 1f, 1f, 0f));
        set.setDuration(700);
        set.start();
    }

    private boolean handleTouch(MotionEvent event) {
        gestures.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                downX = event.getX();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                final float dy = event.getY() - downY;
                final float dx = event.getX() - downX;
                if (!dragging && Math.abs(dy) > touchSlop * 2 && Math.abs(dy) > Math.abs(dx)) {
                    dragging = true;
                    if (holding) {
                        holding = false;
                        if (wasPlayingBeforeHold) resume();
                        setChromeAlpha(1f);
                    }
                }
                if (dragging) {
                    final float translation = Math.max(0, dy);
                    setTranslationY(translation * 0.45f);
                    setAlpha(1f - Math.min(0.25f, translation / Math.max(1f, getHeight())));
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (holding) {
                    holding = false;
                    setChromeAlpha(1f);
                    if (wasPlayingBeforeHold) resume();
                    return true;
                }
                if (dragging) {
                    dragging = false;
                    final float dy = event.getY() - downY;
                    if (dy > dp(110)) {
                        delegate.onClose();
                    } else {
                        animate().translationY(0).alpha(1f).setDuration(160).start();
                    }
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private void startTicker() {
        removeCallbacks(ticker);
        if (active && player != null) postDelayed(ticker, 50);
    }

    private void stopTicker() {
        removeCallbacks(ticker);
    }

    private void applyVideoTransform() {
        if (videoWidth <= 0 || videoHeight <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
        final float scale = Math.max(getWidth() / (float) videoWidth, getHeight() / (float) videoHeight);
        final float dx = (getWidth() - videoWidth * scale) / 2f;
        final float dy = (getHeight() - videoHeight * scale) / 2f;
        final Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        textureView.setTransform(matrix);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyVideoTransform();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopTicker();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
