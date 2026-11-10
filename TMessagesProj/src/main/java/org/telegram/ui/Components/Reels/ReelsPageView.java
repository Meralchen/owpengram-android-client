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
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
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
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

/** One fullscreen clip: video surface, top timeline/creator row and bottom/side action overlay. */
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
    private final View topShade;
    private final View bottomShade;
    private final ActionButton likeButton;
    private final ActionButton commentsButton;
    private final ActionButton shareButton;
    private final ActionButton viewsButton;
    private final ImageView heartView;
    private final FrameLayout overlayContainer;

    private ReelsItem item;
    private ExoPlayer player;
    private boolean attached;
    private boolean overlayVisible = true;
    private float videoAspectRatio = 9f / 16f;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            updateProgressVisibility();
            if (playbackState == Player.STATE_READY) {
                thumbView.animate().alpha(0f).setDuration(ReelsViewer.isReducedMotion() ? 0 : 150).start();
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updatePlayPauseIcon();
            updateProgressVisibility();
            timelineView.setKeepScreenOn(isPlaying);
            if (isPlaying) {
                timelineView.post(timelineUpdater);
            }
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoAspectRatio = (float) videoSize.width / (float) videoSize.height;
                requestLayout();
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            updateProgressVisibility();
            thumbView.setAlpha(1f);
        }
    };

    private final Runnable timelineUpdater = new Runnable() {
        @Override
        public void run() {
            if (player == null || !attached) return;
            timelineView.setProgress(player.getCurrentPosition(), player.getDuration());
            if (player.isPlaying()) {
                postDelayed(this, 16);
            }
        }
    };

    public ReelsPageView(Context context, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        setBackgroundColor(Color.BLACK);

        videoView = new TextureView(context);
        addView(videoView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        thumbView = new BackupImageView(context);
        thumbView.getImageReceiver().setAspectFit(true);
        addView(thumbView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        topShade = new View(context);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x44000000, Color.TRANSPARENT}));
        addView(topShade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 140, Gravity.TOP));

        bottomShade = new View(context);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x66000000, Color.TRANSPARENT}));
        addView(bottomShade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 260, Gravity.BOTTOM));

        overlayContainer = new FrameLayout(context);
        addView(overlayContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Top Timeline
        timelineView = new TimelineView(context, pos -> {
            if (delegate != null) delegate.onSeek(this, pos);
        });
        overlayContainer.addView(timelineView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 24, Gravity.TOP, 16, 36, 16, 0));

        // Creator badge & Caption (Bottom Left)
        LinearLayout infoContainer = new LinearLayout(context);
        infoContainer.setOrientation(LinearLayout.VERTICAL);

        channelRow = new LinearLayout(context);
        channelRow.setOrientation(LinearLayout.HORIZONTAL);
        channelRow.setGravity(Gravity.CENTER_VERTICAL);
        channelRow.setOnClickListener(v -> {
            if (delegate != null) delegate.onOpenChannel(this);
        });

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(18));
        channelRow.addView(avatarView, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(context);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(titleView);

        subtitleView = new TextView(context);
        subtitleView.setTextColor(0xB3FFFFFF);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(subtitleView);
        channelRow.addView(textCol, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        infoContainer.addView(channelRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        captionView = new TextView(context);
        captionView.setTextColor(Color.WHITE);
        captionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        captionView.setMaxLines(3);
        captionView.setEllipsize(TextUtils.TruncateAt.END);
        infoContainer.addView(captionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        overlayContainer.addView(infoContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT, 16, 0, 80, 28));

        // Right Action Column
        LinearLayout actions = new LinearLayout(context);
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
                Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 8, 28));

        progressView = new RadialProgressView(context);
        progressView.setSize(dp(44));
        progressView.setProgressColor(Color.WHITE);
        addView(progressView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        playIcon = new ImageView(context);
        playIcon.setImageResource(R.drawable.play_mini_video);
        playIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        playIcon.setVisibility(GONE);
        addView(playIcon, LayoutHelper.createFrame(64, 64, Gravity.CENTER));

        heartView = new ImageView(context);
        heartView.setImageResource(R.drawable.media_like_active);
        heartView.setColorFilter(new PorterDuffColorFilter(0xFFFF3B30, PorterDuff.Mode.SRC_IN));
        heartView.setVisibility(GONE);
        addView(heartView, LayoutHelper.createFrame(96, 96, Gravity.CENTER));

        setupGestures(context);
    }

    private void setupGestures(Context context) {
        final GestureDetector gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
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
            public void onLongPress(MotionEvent e) {
                toggleCleanMode(false);
            }
        });

        setOnTouchListener((v, event) -> {
            boolean handled = gestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (!overlayVisible) {
                    toggleCleanMode(true);
                }
            }
            return handled || true;
        });
    }

    private void toggleCleanMode(boolean show) {
        overlayVisible = show;
        float targetAlpha = show ? 1f : 0f;
        int duration = ReelsViewer.isReducedMotion() ? 0 : 200;
        topShade.animate().alpha(targetAlpha).setDuration(duration).start();
        bottomShade.animate().alpha(targetAlpha).setDuration(duration).start();
        overlayContainer.animate().alpha(targetAlpha).setDuration(duration).start();
    }

    public void animateHeart(float x, float y) {
        heartView.setX(x - heartView.getWidth() / 2f);
        heartView.setY(y - heartView.getHeight() / 2f);
        heartView.setVisibility(VISIBLE);
        heartView.setScaleX(0.2f);
        heartView.setScaleY(0.2f);
        heartView.setAlpha(0f);

        AnimatorSet set = new AnimatorSet();
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
        thumbView.setAlpha(1f);
        if (item.source == ReelsItem.Source.CHANNEL) {
            bindChannel(item);
        } else {
            bindProfile(item);
        }
        captionView.setText(item.getCaption() != null ? item.getCaption() : "");
        captionView.setVisibility(TextUtils.isEmpty(item.getCaption()) ? GONE : VISIBLE);
        updateCounters();
    }

    private void bindChannel(ReelsItem item) {
        MessagesController controller = MessagesController.getInstance(item.account);
        TLRPC.Chat chat = controller.getChat(item.channelId);
        if (chat != null) {
            titleView.setText(chat.title);
            subtitleView.setText(chat.username != null ? "@" + chat.username : "");
            avatarDrawable.setInfo(chat);
            avatarView.setForUserOrChat(chat, avatarDrawable);
        }
        if (item.document != null) {
            thumbView.setImage(ImageLocation.getForDocument(item.document), "320_180", null, null, item.messageObject);
        }
    }

    private void bindProfile(ReelsItem item) {
        final String name = !TextUtils.isEmpty(item.creatorName) ? item.creatorName : item.creatorUsername;
        titleView.setText(!TextUtils.isEmpty(name) ? name : "User");
        subtitleView.setText(!TextUtils.isEmpty(item.creatorUsername) ? "@" + item.creatorUsername : "");
        avatarDrawable.setInfo(item.creatorUsername != null ? item.creatorUsername.hashCode() : 0, name, null);
        avatarView.setImageDrawable(avatarDrawable);
        if (!TextUtils.isEmpty(item.thumbnailUrl)) {
            thumbView.setImage(ImageLocation.getForPath(item.thumbnailUrl), "1280_1280", (android.graphics.drawable.Drawable) null, null);
        } else {
            thumbView.clearImage();
        }
    }

    public void updateCounters() {
        if (item == null) return;
        likeButton.setIcon(item.liked ? R.drawable.media_like_active : R.drawable.media_like,
                item.liked ? 0xFFFF3B30 : Color.WHITE);
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
        boolean loading = player.getPlaybackState() == Player.STATE_BUFFERING;
        progressView.setVisibility(loading ? VISIBLE : GONE);
    }

    public void updatePlayPauseIcon() {
        if (player == null) {
            playIcon.setVisibility(GONE);
            return;
        }
        boolean playing = player.isPlaying();
        if (!playing && player.getPlaybackState() == Player.STATE_READY) {
            playIcon.setScaleX(0.8f);
            playIcon.setScaleY(0.8f);
            playIcon.setAlpha(0f);
            playIcon.setVisibility(VISIBLE);
            playIcon.animate().alpha(1f).scaleX(1.1f).scaleY(1.1f).setDuration(180).start();
        } else {
            playIcon.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(180)
                    .withEndAction(() -> playIcon.setVisibility(GONE)).start();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (videoView != null && width > 0 && height > 0) {
            float viewRatio = (float) width / (float) height;
            int vWidth, vHeight;
            if (viewRatio > videoAspectRatio) {
                vWidth = width;
                vHeight = (int) (width / videoAspectRatio);
            } else {
                vHeight = height;
                vWidth = (int) (height * videoAspectRatio);
            }
            videoView.measure(MeasureSpec.makeMeasureSpec(vWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(vHeight, MeasureSpec.EXACTLY));
        }
    }

    // Timeline view for top progress
    private static class TimelineView extends View {
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long currentPosition;
        private long duration;
        private final UtilitiesCallback seekCallback;

        interface UtilitiesCallback {
            void onSeek(long positionMs);
        }

        public TimelineView(Context context, UtilitiesCallback seekCallback) {
            super(context);
            this.seekCallback = seekCallback;
            progressPaint.setColor(Color.WHITE);
            backgroundPaint.setColor(0x44FFFFFF);
        }

        private int lastRenderedWidth = -1;

        // Bolt optimization: avoid redundant view invalidations during the 60fps playback loop
        // when the visible progress bar pixel width has not changed.
        public void setProgress(long current, long total) {
            if (this.currentPosition == current && this.duration == total) {
                return;
            }
            this.currentPosition = current;
            this.duration = total;
            int viewWidth = getWidth();
            if (viewWidth > 0 && total > 0) {
                int progressPx = (int) (viewWidth * Math.min(1f, (float) current / (float) total));
                if (progressPx == lastRenderedWidth) {
                    return;
                }
                lastRenderedWidth = progressPx;
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = dp(2.5f);
            float y = (getHeight() - h) / 2f;
            canvas.drawRect(0, y, getWidth(), y + h, backgroundPaint);
            if (duration > 0) {
                float progress = Math.min(1f, (float) currentPosition / (float) duration);
                float progressWidth = getWidth() * progress;
                lastRenderedWidth = (int) progressWidth;
                canvas.drawRect(0, y, progressWidth, y + h, progressPaint);
            }
        }
    }

    // Action button on right
    private static class ActionButton extends LinearLayout {
        private final ImageView iconView;
        private final TextView textView;

        public ActionButton(Context context, int iconRes, Runnable onClick) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(6), dp(10), dp(6), dp(10));

            iconView = new ImageView(context);
            iconView.setImageResource(iconRes);
            iconView.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            addView(iconView, LayoutHelper.createLinear(32, 32));

            textView = new TextView(context);
            textView.setTextColor(Color.WHITE);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            textView.setGravity(Gravity.CENTER);
            addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            if (onClick != null) {
                setOnClickListener(v -> onClick.run());
            }
        }

        public void setIcon(int res, int color) {
            iconView.setImageResource(res);
            iconView.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        }

        public void setText(String text) {
            textView.setText(text);
            textView.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);
        }
    }
}
