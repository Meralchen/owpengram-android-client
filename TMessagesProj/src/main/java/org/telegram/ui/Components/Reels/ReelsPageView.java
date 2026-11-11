package org.telegram.ui.Components.Reels;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
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

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

/**
 * One fullscreen vertical clip. Follows Telegram's media viewer: black stage, white overlay
 * chrome, thin bottom progress, right action rail and bottom-left creator/caption. All colours
 * and dimensions come from {@link ReelsTheme}.
 */
@OptIn(markerClass = UnstableApi.class)
@SuppressLint("ViewConstructor")
public class ReelsPageView extends FrameLayout {

    public interface Delegate {
        void onToggleLike(ReelsPageView page, boolean fromDoubleTap);
        void onOpenComments(ReelsPageView page);
        void onShare(ReelsPageView page);
        void onOpenChannel(ReelsPageView page);
        void onTogglePlay(ReelsPageView page);
        void onSeek(ReelsPageView page, long positionMs);
    }

    private final Delegate delegate;

    private final TextureView videoView;
    private final BackupImageView thumbView;
    private final RadialProgressView progressView;
    private final ImageView playIcon;
    private final TimelineView timelineView;
    private final BackupImageView avatarView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final TextView titleView;
    private final TextView subtitleView;
    private final LinearLayout channelRow;
    private final TextView captionView;
    private final TextView errorView;
    private final View topShade;
    private final View bottomShade;
    private final ActionButton likeButton;
    private final ActionButton commentsButton;
    private final ActionButton shareButton;
    private final ActionButton viewsButton;
    private final ImageView heartView;

    private ReelsItem item;
    private ExoPlayer player;
    private boolean attached;
    private boolean overlayVisible = true;
    private float videoAspectRatio = 9f / 16f;
    private int videoWidth;
    private int videoHeight;
    private final int bottomInset;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            updateProgressVisibility();
            errorView.setVisibility(GONE);
            if (playbackState == Player.STATE_READY) {
                thumbView.animate().alpha(0f).setDuration(ReelsViewer.isReducedMotion() ? 0 : 150).start();
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updatePlayPauseIcon();
            updateProgressVisibility();
            setKeepScreenOn(isPlaying);
            if (isPlaying) {
                removeCallbacks(timelineUpdater);
                post(timelineUpdater);
            } else {
                removeCallbacks(timelineUpdater);
            }
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoWidth = videoSize.width;
                videoHeight = videoSize.height;
                videoAspectRatio = (float) videoWidth / (float) videoHeight;
                applyVideoTransform();
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            updateProgressVisibility();
            thumbView.setAlpha(1f);
            errorView.setVisibility(VISIBLE);
        }
    };

    private final Runnable timelineUpdater = new Runnable() {
        @Override
        public void run() {
            if (player == null || !attached) return;
            timelineView.setProgress(player.getCurrentPosition(), player.getDuration());
            if (player.isPlaying()) {
                postDelayed(this, 50);
            }
        }
    };

    public ReelsPageView(Context context, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        bottomInset = Math.max(0, AndroidUtilities.navigationBarHeight);
        setBackgroundColor(ReelsTheme.viewerBackground);

        videoView = new TextureView(context);
        addView(videoView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        thumbView = new BackupImageView(context);
        thumbView.getImageReceiver().setAspectFit(true);
        addView(thumbView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        topShade = new View(context);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ReelsTheme.viewerScrimTop, ReelsTheme.viewerScrimTopMid, Color.TRANSPARENT}));
        addView(topShade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 140, Gravity.TOP));

        bottomShade = new View(context);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{ReelsTheme.viewerScrimBottom, ReelsTheme.viewerScrimBottomMid, Color.TRANSPARENT}));
        addView(bottomShade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 280, Gravity.BOTTOM));

        final FrameLayout overlayContainer = new FrameLayout(context);
        addView(overlayContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Bottom progress with elapsed/total time, like Telegram's media viewer.
        timelineView = new TimelineView(context, pos -> {
            if (delegate != null) delegate.onSeek(this, pos);
        });
        overlayContainer.addView(timelineView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 26,
                Gravity.BOTTOM, ReelsTheme.sideMargin, 0, ReelsTheme.sideMargin, 4 + bottomInset));

        // Creator + caption, bottom left.
        final LinearLayout infoContainer = new LinearLayout(context);
        infoContainer.setOrientation(LinearLayout.VERTICAL);

        channelRow = new LinearLayout(context);
        channelRow.setOrientation(LinearLayout.HORIZONTAL);
        channelRow.setGravity(Gravity.CENTER_VERTICAL);
        channelRow.setMinimumHeight(dp(ReelsTheme.touchTarget));
        channelRow.setOnClickListener(v -> {
            if (delegate != null) delegate.onOpenChannel(this);
        });

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(ReelsTheme.avatarSize / 2f));
        channelRow.addView(avatarView, LayoutHelper.createLinear(ReelsTheme.avatarSize, ReelsTheme.avatarSize,
                Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        final LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(context);
        titleView.setTextColor(ReelsTheme.viewerPrimary);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(titleView);

        subtitleView = new TextView(context);
        subtitleView.setTextColor(ReelsTheme.viewerSecondary);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(subtitleView);
        channelRow.addView(textCol, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        infoContainer.addView(channelRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        captionView = new TextView(context);
        captionView.setTextColor(ReelsTheme.viewerPrimary);
        captionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        captionView.setMaxLines(3);
        captionView.setEllipsize(TextUtils.TruncateAt.END);
        infoContainer.addView(captionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        overlayContainer.addView(infoContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT, ReelsTheme.sideMargin, 0, 76, 34 + bottomInset));

        // Right action rail: like, comment, share, views.
        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER_HORIZONTAL);

        likeButton = new ActionButton(context, R.drawable.media_like, () -> {
            if (delegate != null) delegate.onToggleLike(this, false);
        });
        actions.addView(likeButton);

        commentsButton = new ActionButton(context, R.drawable.menu_comments, () -> {
            if (delegate != null) delegate.onOpenComments(this);
        });
        actions.addView(commentsButton);

        shareButton = new ActionButton(context, R.drawable.msg_share, () -> {
            if (delegate != null) delegate.onShare(this);
        });
        actions.addView(shareButton);

        viewsButton = new ActionButton(context, R.drawable.msg_views, null);
        actions.addView(viewsButton);

        overlayContainer.addView(actions, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 4, 30 + bottomInset));

        progressView = new RadialProgressView(context);
        progressView.setSize(dp(44));
        progressView.setProgressColor(ReelsTheme.viewerPrimary);
        addView(progressView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        playIcon = new ImageView(context);
        playIcon.setImageResource(R.drawable.play_mini_video);
        playIcon.setColorFilter(new PorterDuffColorFilter(ReelsTheme.viewerPrimary, PorterDuff.Mode.SRC_IN));
        playIcon.setVisibility(GONE);
        addView(playIcon, LayoutHelper.createFrame(64, 64, Gravity.CENTER));

        errorView = new TextView(context);
        errorView.setTextColor(ReelsTheme.viewerSecondary);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(dp(24), dp(10), dp(24), dp(10));
        errorView.setBackground(ReelsTheme.rounded(ReelsTheme.viewerShadow, 14));
        errorView.setText(LocaleController.getString(R.string.ShortsLoadFailed));
        errorView.setVisibility(GONE);
        errorView.setOnClickListener(v -> {
            if (player != null) {
                errorView.setVisibility(GONE);
                player.prepare();
            }
        });
        addView(errorView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        heartView = new ImageView(context);
        heartView.setImageResource(R.drawable.media_like_active);
        heartView.setColorFilter(new PorterDuffColorFilter(ReelsTheme.viewerLike, PorterDuff.Mode.SRC_IN));
        heartView.setVisibility(GONE);
        addView(heartView, LayoutHelper.createFrame(96, 96, Gravity.CENTER));

        setupGestures(context);
    }

    private void setupGestures(Context context) {
        final GestureDetector gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (delegate != null) delegate.onTogglePlay(ReelsPageView.this);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                animateHeart(e.getX(), e.getY());
                if (delegate != null) delegate.onToggleLike(ReelsPageView.this, true);
                return true;
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                toggleCleanMode(false);
            }
        });

        setOnTouchListener((v, event) -> {
            final boolean handled = gestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (!overlayVisible) {
                    toggleCleanMode(true);
                }
            }
            return handled;
        });
    }

    private void toggleCleanMode(boolean show) {
        overlayVisible = show;
        final float targetAlpha = show ? 1f : 0f;
        final int duration = ReelsViewer.isReducedMotion() ? 0 : 200;
        topShade.animate().alpha(targetAlpha).setDuration(duration).start();
        bottomShade.animate().alpha(targetAlpha).setDuration(duration).start();
        if (timelineView.getParent() instanceof View) {
            ((View) timelineView.getParent()).animate().alpha(targetAlpha).setDuration(duration).start();
        }
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child == progressView || child == playIcon || child == heartView || child == errorView
                    || child == videoView || child == thumbView) {
                continue;
            }
            child.animate().alpha(targetAlpha).setDuration(duration).start();
        }
    }

    public void animateHeart(float x, float y) {
        heartView.setX(x - heartView.getWidth() / 2f);
        heartView.setY(y - heartView.getHeight() / 2f);
        heartView.setVisibility(VISIBLE);
        heartView.setScaleX(0.2f);
        heartView.setScaleY(0.2f);
        heartView.setAlpha(0f);

        if (ReelsViewer.isReducedMotion()) {
            heartView.setAlpha(1f);
            heartView.setScaleX(1f);
            heartView.setScaleY(1f);
            postDelayed(() -> heartView.setVisibility(GONE), 220);
            return;
        }

        final AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(heartView, View.ALPHA, 0f, 1f, 1f, 0f),
                ObjectAnimator.ofFloat(heartView, View.SCALE_X, 0.2f, 1.25f, 1.0f, 0.8f),
                ObjectAnimator.ofFloat(heartView, View.SCALE_Y, 0.2f, 1.25f, 1.0f, 0.8f)
        );
        set.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        set.setDuration(800);
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                heartView.setVisibility(GONE);
            }
        });
        set.start();
    }

    public void bind(ReelsItem item, int position) {
        this.item = item;
        thumbView.setAlpha(player != null && player.isPlaying() ? 0f : 1f);
        errorView.setVisibility(GONE);
        if (item.source == ReelsItem.Source.CHANNEL) {
            bindChannel(item);
        } else {
            bindProfile(item);
        }
        captionView.setText(item.getCaption() != null ? item.getCaption() : "");
        captionView.setVisibility(TextUtils.isEmpty(item.getCaption()) ? GONE : VISIBLE);
        videoWidth = item.getVideoWidth();
        videoHeight = item.getVideoHeight();
        if (videoWidth > 0 && videoHeight > 0) {
            videoAspectRatio = (float) videoWidth / (float) videoHeight;
        } else {
            videoAspectRatio = 9f / 16f;
        }
        updateCounters();
        applyVideoTransform();
    }

    private void bindChannel(ReelsItem item) {
        final MessagesController controller = MessagesController.getInstance(item.account);
        final TLRPC.Chat chat = controller.getChat(item.channelId);
        if (chat != null) {
            titleView.setText(chat.title);
            subtitleView.setText(chat.username != null ? "@" + chat.username : "");
            avatarDrawable.setInfo(chat);
            avatarView.setForUserOrChat(chat, avatarDrawable);
        }
        if (item.document != null) {
            thumbView.setImage(ImageLocation.getForDocument(item.document), "320_480", null, null, item.messageObject);
        }
    }

    private void bindProfile(ReelsItem item) {
        final String name = !TextUtils.isEmpty(item.creatorName) ? item.creatorName : item.creatorUsername;
        titleView.setText(!TextUtils.isEmpty(name) ? name : "User");
        subtitleView.setText(!TextUtils.isEmpty(item.creatorUsername) ? "@" + item.creatorUsername : "");
        final TLRPC.User user = TextUtils.isEmpty(item.creatorUsername)
                ? null : MessagesController.getInstance(item.account).getUser(item.creatorUsername);
        if (user != null) {
            avatarDrawable.setInfo(user);
            avatarView.setForUserOrChat(user, avatarDrawable);
        } else {
            avatarDrawable.setInfo(item.creatorUsername != null ? item.creatorUsername.hashCode() : 0, name, null);
            avatarView.setImageDrawable(avatarDrawable);
        }
        if (!TextUtils.isEmpty(item.thumbnailUrl)) {
            thumbView.setImage(ImageLocation.getForPath(item.thumbnailUrl), "1280_1280", (android.graphics.drawable.Drawable) null, null);
        } else {
            thumbView.clearImage();
        }
    }

    public void updateCounters() {
        if (item == null) return;
        likeButton.setIcon(item.liked ? R.drawable.media_like_active : R.drawable.media_like,
                item.liked ? ReelsTheme.viewerLike : ReelsTheme.viewerPrimary);
        likeButton.setText(item.likes > 0 ? String.valueOf(item.likes) : "");
        commentsButton.setText(item.comments > 0 ? String.valueOf(item.comments) : "");
        viewsButton.setText(item.views > 0 ? String.valueOf(item.views) : "");
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (player != null && player.isPlaying()) {
            removeCallbacks(timelineUpdater);
            post(timelineUpdater);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        removeCallbacks(timelineUpdater);
        setKeepScreenOn(false);
    }

    public void attachPlayer(ExoPlayer newPlayer) {
        if (player == newPlayer) return;
        if (player != null) {
            player.removeListener(playerListener);
        }
        player = newPlayer;
        if (player != null) {
            player.addListener(playerListener);
            updateProgressVisibility();
            updatePlayPauseIcon();
            if (player.isPlaying()) {
                thumbView.animate().alpha(0f).setDuration(ReelsViewer.isReducedMotion() ? 0 : 150).start();
                removeCallbacks(timelineUpdater);
                post(timelineUpdater);
            }
        }
    }

    public void detachPlayer() {
        if (player != null) {
            player.removeListener(playerListener);
            player = null;
        }
        removeCallbacks(timelineUpdater);
        setKeepScreenOn(false);
        thumbView.setAlpha(1f);
    }

    public TextureView getVideoView() {
        return videoView;
    }

    public ReelsItem getItem() {
        return item;
    }

    private void updateProgressVisibility() {
        if (player == null) {
            progressView.setVisibility(GONE);
            return;
        }
        final boolean loading = player.getPlaybackState() == Player.STATE_BUFFERING;
        progressView.setVisibility(loading ? VISIBLE : GONE);
    }

    public void updatePlayPauseIcon() {
        if (player == null) {
            playIcon.setVisibility(GONE);
            return;
        }
        final boolean playing = player.isPlaying();
        if (!playing && player.getPlaybackState() == Player.STATE_READY) {
            if (playIcon.getVisibility() != VISIBLE) {
                playIcon.setScaleX(0.8f);
                playIcon.setScaleY(0.8f);
                playIcon.setAlpha(0f);
                playIcon.setVisibility(VISIBLE);
                if (ReelsViewer.isReducedMotion()) {
                    playIcon.setAlpha(1f);
                    playIcon.setScaleX(1f);
                    playIcon.setScaleY(1f);
                } else {
                    playIcon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
                }
            }
        } else {
            playIcon.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(180)
                    .withEndAction(() -> playIcon.setVisibility(GONE)).start();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyVideoTransform();
    }

    /** Center-crops the video into the page, like Telegram's fullscreen media viewer. */
    private void applyVideoTransform() {
        final int width = getWidth();
        final int height = getHeight();
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0 || videoView == null) {
            return;
        }
        final float scale = Math.max((float) width / (float) videoWidth, (float) height / (float) videoHeight);
        final Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate((width - videoWidth * scale) / 2f, (height - videoHeight * scale) / 2f);
        videoView.setTransform(matrix);
    }

    /** Thin bottom progress line with elapsed/total time and drag-to-seek. */
    private static class TimelineView extends View {
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SeekCallback seekCallback;
        private final int touchSlop;

        private long currentPosition;
        private long duration;
        private int lastProgressWidth = -1;
        private int lastSeconds = -1;
        private boolean seeking;
        private float downX;
        private float downY;

        interface SeekCallback {
            void onSeek(long positionMs);
        }

        TimelineView(Context context, SeekCallback seekCallback) {
            super(context);
            this.seekCallback = seekCallback;
            progressPaint.setColor(ReelsTheme.viewerPrimary);
            backgroundPaint.setColor(ReelsTheme.viewerTrack);
            textPaint.setColor(ReelsTheme.viewerSecondary);
            textPaint.setTextSize(dp(10));
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClickable(true);
        }

        // Avoid redundant invalidations while the progress pixel width and label are unchanged.
        public void setProgress(long current, long total) {
            final int seconds = (int) (current / 1000);
            final int viewWidth = getWidth();
            final int progressPx = viewWidth > 0 && total > 0
                    ? (int) (viewWidth * Math.min(1f, (float) current / (float) total)) : 0;
            if (this.currentPosition == current && this.duration == total
                    && progressPx == lastProgressWidth && seconds == lastSeconds) {
                return;
            }
            this.currentPosition = current;
            this.duration = total;
            this.lastSeconds = seconds;
            this.lastProgressWidth = progressPx;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float barHeight = dp(ReelsTheme.progressHeight);
            final float barY = getHeight() - barHeight - dp(1);
            canvas.drawRect(0, barY, getWidth(), barY + barHeight, backgroundPaint);
            if (duration > 0) {
                final float progress = Math.min(1f, (float) currentPosition / (float) duration);
                canvas.drawRect(0, barY, getWidth() * progress, barY + barHeight, progressPaint);
            }

            final int seconds = (int) (currentPosition / 1000);
            final int totalSeconds = (int) (duration / 1000);
            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(AndroidUtilities.formatDuration(seconds, false), 0, barY - dp(6), textPaint);
            if (totalSeconds > 0) {
                textPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(AndroidUtilities.formatDuration(totalSeconds, false), getWidth(), barY - dp(6), textPaint);
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    seeking = true;
                    downX = event.getX();
                    downY = event.getY();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getY() - downY) > touchSlop
                            && Math.abs(event.getY() - downY) > Math.abs(event.getX() - downX)) {
                        seeking = false;
                        getParent().requestDisallowInterceptTouchEvent(false);
                        return false;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (seeking && duration > 0) {
                        final float fraction = Math.max(0f, Math.min(1f, event.getX() / Math.max(1f, getWidth())));
                        if (seekCallback != null) {
                            seekCallback.onSeek((long) (duration * fraction));
                        }
                        performClick();
                    }
                    seeking = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    seeking = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return false;
            }
            return super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    /** Rail button: 28 dp icon, 12 sp count, 48 dp touch target, white with a soft shadow. */
    private static class ActionButton extends LinearLayout {
        private final ImageView iconView;
        private final TextView textView;

        ActionButton(Context context, int iconRes, Runnable onClick) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setMinimumWidth(dp(ReelsTheme.touchTarget));
            setMinimumHeight(dp(ReelsTheme.touchTarget));
            setPadding(dp(10), dp(8), dp(10), dp(8));

            iconView = new ImageView(context);
            iconView.setImageResource(iconRes);
            iconView.setColorFilter(new PorterDuffColorFilter(ReelsTheme.viewerPrimary, PorterDuff.Mode.SRC_IN));
            addView(iconView, LayoutHelper.createLinear(ReelsTheme.actionIconSize, ReelsTheme.actionIconSize));

            textView = new TextView(context);
            textView.setTextColor(ReelsTheme.viewerPrimary);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            textView.setTypeface(AndroidUtilities.bold());
            textView.setGravity(Gravity.CENTER);
            textView.setShadowLayer(dp(2), 0, dp(1), ReelsTheme.viewerShadow);
            addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

            if (onClick != null) {
                setOnClickListener(v -> onClick.run());
            }
        }

        void setIcon(int res, int color) {
            iconView.setImageResource(res);
            iconView.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        }

        void setText(String text) {
            textView.setText(text);
            textView.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);
        }
    }
}
