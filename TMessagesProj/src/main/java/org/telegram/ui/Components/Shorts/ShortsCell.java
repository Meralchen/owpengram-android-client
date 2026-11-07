package org.telegram.ui.Components.Shorts;

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
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
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
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

/** One fullscreen clip: video surface, story-like top timeline/creator row and bottom action overlay. */
@OptIn(markerClass = UnstableApi.class)
@SuppressLint("ViewConstructor")
public class ShortsCell extends FrameLayout {

    public interface Delegate {
        void onToggleLike(ShortsCell cell, boolean fromDoubleTap);

        void onOpenComments(ShortsCell cell);

        void onShare(ShortsCell cell);

        void onOpenChannel(ShortsCell cell);

        void onTogglePlay(ShortsCell cell);

        void onSeek(ShortsCell cell, long positionMs);

        void onMovePage(ShortsCell cell, int delta);
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
    private final ActionButton likeButton;
    private final ActionButton commentsButton;
    private final ActionButton shareButton;
    private final ActionButton viewsButton;
    private final BackupImageView heartEffectView;
    private final ImageView heartView;

    private final GestureDetector gestureDetector;

    private ShortsItem item;
    private int position = -1;
    private ExoPlayer player;
    private int videoWidth, videoHeight;
    private boolean userPaused;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoWidth = (int) (videoSize.width * videoSize.pixelWidthHeightRatio);
                videoHeight = videoSize.height;
                requestLayout();
            }
        }

        @Override
        public void onRenderedFirstFrame() {
            thumbView.animate().alpha(0f).setDuration(ShortsActivity.isReducedMotion() ? 0 : 150).start();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            updateBuffering();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updatePlayIcon();
            if (isPlaying) {
                postOnAnimation(progressTicker);
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            updateBuffering();
        }
    };

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (player == null) {
                return;
            }
            timelineView.setProgress(player.getCurrentPosition(), duration());
            if (player.isPlaying()) {
                postOnAnimationDelayed(this, 100);
            }
        }
    };

    public ShortsCell(Context context, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        setBackgroundColor(Color.BLACK);

        thumbView = new BackupImageView(context);
        addView(thumbView);
        videoView = new TextureView(context);
        addView(videoView);

        progressView = new RadialProgressView(context);
        progressView.setProgressColor(Color.WHITE);
        progressView.setSize(dp(42));
        progressView.setVisibility(GONE);
        addView(progressView, LayoutHelper.createFrame(56, 56, Gravity.CENTER));

        playIcon = new ImageView(context);
        playIcon.setImageResource(R.drawable.play_mini_video);
        playIcon.setScaleType(ImageView.ScaleType.CENTER);
        playIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        playIcon.setBackground(circle(0x66000000));
        playIcon.setScaleX(1.6f);
        playIcon.setScaleY(1.6f);
        playIcon.setVisibility(GONE);
        playIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(playIcon, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        heartEffectView = new BackupImageView(context);
        heartEffectView.setVisibility(GONE);
        heartEffectView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(heartEffectView, LayoutHelper.createFrame(220, 220, Gravity.CENTER));
        heartView = new ImageView(context);
        heartView.setImageResource(R.drawable.media_like_active);
        heartView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        heartView.setVisibility(GONE);
        heartView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(heartView, LayoutHelper.createFrame(96, 96, Gravity.CENTER));

        final View topShade = new View(context);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0}));
        addView(topShade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 140, Gravity.TOP));
        final View bottomShade = new View(context);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xb3000000, 0}));
        addView(bottomShade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 220, Gravity.BOTTOM));

        final LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.VERTICAL);
        addView(top, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 0, 0, 0, 0));
        top.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

        timelineView = new TimelineView(context);
        top.addView(timelineView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 30, 0, 6, 0, 0));

        channelRow = new LinearLayout(context);
        channelRow.setOrientation(LinearLayout.HORIZONTAL);
        channelRow.setGravity(Gravity.CENTER_VERTICAL);
        channelRow.setPadding(dp(12), dp(4), dp(12), dp(4));
        top.addView(channelRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 52, 0, 0, 0));
        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(18));
        channelRow.addView(avatarView, LayoutHelper.createLinear(36, 36));
        final LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        channelRow.addView(names, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 10, 0, 0, 0));
        titleView = text(context, 15, true);
        names.addView(titleView);
        subtitleView = text(context, 13, false);
        subtitleView.setAlpha(0.8f);
        names.addView(subtitleView);
        priorityTouch(channelRow);
        channelRow.setOnClickListener(v -> delegate.onOpenChannel(this));

        final LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        addView(bottom, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        bottom.setPadding(0, 0, 0, dp(8) + AndroidUtilities.navigationBarHeight);

        captionView = text(context, 14, false);
        captionView.setMaxLines(3);
        captionView.setEllipsize(TextUtils.TruncateAt.END);
        bottom.addView(captionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 8));

        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 8, 0, 8, 0));
        likeButton = new ActionButton(context, R.drawable.media_like);
        likeButton.setOnClickListener(v -> delegate.onToggleLike(this, false));
        actions.addView(likeButton, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        commentsButton = new ActionButton(context, R.drawable.menu_comments);
        commentsButton.setOnClickListener(v -> delegate.onOpenComments(this));
        actions.addView(commentsButton, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        shareButton = new ActionButton(context, R.drawable.msg_share);
        shareButton.setOnClickListener(v -> delegate.onShare(this));
        shareButton.setContentDescription(LocaleController.getString(R.string.ShortsShare));
        actions.addView(shareButton, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        viewsButton = new ActionButton(context, R.drawable.msg_views);
        viewsButton.setClickable(false);
        actions.addView(viewsButton, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            // Fires only after the double-tap timeout, so a double tap never also toggles playback.
            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                delegate.onTogglePlay(ShortsCell.this);
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (item != null && !item.liked) {
                    delegate.onToggleLike(ShortsCell.this, true);
                }
                showHeart();
                return true;
            }
        });
    }

    private static GradientDrawable circle(int color) {
        final GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private static TextView text(Context context, int sizeDp, boolean bold) {
        final TextView view = new TextView(context);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setSingleLine(bold);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setShadowLayer(dp(2), 0, dp(1), 0x66000000);
        if (bold) {
            view.setTypeface(AndroidUtilities.bold());
        }
        return view;
    }

    /** Controls keep the gesture: the pager neither pages nor fires vertical actions. */
    @SuppressLint("ClickableViewAccessibility")
    private static void priorityTouch(View view) {
        view.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && v.getParent() != null) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
    }

    public void bind(ShortsItem item, int position) {
        final boolean changed = this.item != item;
        this.item = item;
        this.position = position;
        if (changed) {
            userPaused = false;
            videoWidth = item.getVideoWidth();
            videoHeight = item.getVideoHeight();
            thumbView.animate().cancel();
            thumbView.setAlpha(1f);
            final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(item.document.thumbs, 1280);
            thumbView.setImage(ImageLocation.getForDocument(thumb, item.document), null, (android.graphics.drawable.Drawable) null, item.messageObject);
            final TLRPC.Chat chat = MessagesController.getInstance(item.account).getChat(item.channelId);
            avatarDrawable.setInfo(item.account, chat);
            avatarView.setForUserOrChat(chat, avatarDrawable);
            titleView.setText(chat != null ? chat.title : "");
            final String username = chat != null ? ChatObject.getPublicUsername(chat) : null;
            subtitleView.setText(username != null ? "@" + username : LocaleController.formatDateAudio(item.getMessage().date, false));
            final String caption = item.getCaption();
            captionView.setText(caption);
            captionView.setVisibility(caption != null ? VISIBLE : GONE);
            timelineView.setProgress(item.resumePositionMs, duration());
            requestLayout();
        }
        updateCounters();
        updatePlayIcon();
    }

    public ShortsItem getItem() {
        return item;
    }

    public int getPosition() {
        return position;
    }

    public TextureView getVideoView() {
        return videoView;
    }

    public void setPlayer(ExoPlayer newPlayer) {
        if (player == newPlayer) {
            return;
        }
        if (player != null) {
            player.removeListener(playerListener);
        }
        removeCallbacks(progressTicker);
        player = newPlayer;
        if (player != null) {
            player.addListener(playerListener);
            final VideoSize size = player.getVideoSize();
            if (size.width > 0 && size.height > 0) {
                playerListener.onVideoSizeChanged(size);
            }
            postOnAnimation(progressTicker);
        }
        updateBuffering();
        updatePlayIcon();
    }

    public ExoPlayer getPlayer() {
        return player;
    }

    public boolean isUserPaused() {
        return userPaused;
    }

    public void setUserPaused(boolean paused) {
        userPaused = paused;
        updatePlayIcon();
    }

    public void updateCounters() {
        if (item == null) {
            return;
        }
        likeButton.setIcon(item.liked ? R.drawable.media_like_active : R.drawable.media_like, item.liked ? 0xFFFF2D55 : Color.WHITE);
        likeButton.setCount(item.likes);
        likeButton.setContentDescription(LocaleController.formatString(item.liked ? R.string.ShortsUnlike : R.string.ShortsLike, item.likes));
        commentsButton.setCount(item.comments);
        commentsButton.setAlpha(item.commentsEnabled ? 1f : 0.5f);
        commentsButton.setContentDescription(LocaleController.formatString(R.string.ShortsCommentsCount, item.comments));
        viewsButton.setCount(item.views);
        viewsButton.setContentDescription(LocaleController.formatString(R.string.ShortsViewsCount, item.views));
    }

    private long duration() {
        if (player != null && player.getDuration() > 0) {
            return player.getDuration();
        }
        return item != null ? (long) (item.getDuration() * 1000) : 0;
    }

    private void updateBuffering() {
        final boolean buffering = player == null || player.getPlaybackState() == Player.STATE_BUFFERING
                || player.getPlaybackState() == Player.STATE_IDLE && player.getPlayerError() == null;
        progressView.setVisibility(buffering && !userPaused ? VISIBLE : GONE);
    }

    private void updatePlayIcon() {
        playIcon.setVisibility(userPaused ? VISIBLE : GONE);
    }

    public void showHeart() {
        heartView.animate().cancel();
        if (ShortsActivity.isReducedMotion()) {
            heartView.setVisibility(VISIBLE);
            heartView.setAlpha(1f);
            heartView.setScaleX(1f);
            heartView.setScaleY(1f);
            heartView.animate().alpha(0f).setStartDelay(400).setDuration(0).withEndAction(() -> heartView.setVisibility(GONE)).start();
            return;
        }
        final TLRPC.TL_availableReaction heart = item != null ? MediaDataController.getInstance(item.account).getReactionsMap().get(ShortsItem.HEART) : null;
        if (heart != null && heart.effect_animation != null) {
            heartEffectView.setVisibility(VISIBLE);
            heartEffectView.getImageReceiver().setUniqKeyPrefix(System.nanoTime() + "_");
            heartEffectView.setImage(ImageLocation.getForDocument(heart.effect_animation), "220_220", null, null, 0, null);
            heartEffectView.getImageReceiver().setAutoRepeat(0);
            if (heartEffectView.getImageReceiver().getLottieAnimation() != null) {
                heartEffectView.getImageReceiver().getLottieAnimation().setCurrentFrame(0, false);
                heartEffectView.getImageReceiver().getLottieAnimation().start();
            }
            heartEffectView.removeCallbacks(hideEffect);
            heartEffectView.postDelayed(hideEffect, 2000);
        }
        heartView.setVisibility(VISIBLE);
        heartView.setAlpha(0f);
        heartView.setScaleX(0.4f);
        heartView.setScaleY(0.4f);
        final AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(heartView, View.ALPHA, 0f, 1f, 1f, 0f),
                ObjectAnimator.ofFloat(heartView, View.SCALE_X, 0.4f, 1.2f, 1f, 1.1f),
                ObjectAnimator.ofFloat(heartView, View.SCALE_Y, 0.4f, 1.2f, 1f, 1.1f));
        set.setDuration(700);
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                heartView.setVisibility(GONE);
            }
        });
        set.start();
    }

    private final Runnable hideEffect = this::hideHeartEffect;

    private void hideHeartEffect() {
        heartEffectView.setVisibility(GONE);
        heartEffectView.clearImage();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        final int w = right - left, h = bottom - top;
        int vw = w, vh = h;
        if (videoWidth > 0 && videoHeight > 0) {
            final float scale = Math.min(w / (float) videoWidth, h / (float) videoHeight);
            vw = Math.round(videoWidth * scale);
            vh = Math.round(videoHeight * scale);
        }
        final int l = (w - vw) / 2, t = (h - vh) / 2;
        videoView.layout(l, t, l + vw, t + vh);
        thumbView.layout(l, t, l + vw, t + vh);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final int w = getMeasuredWidth(), h = getMeasuredHeight();
        videoView.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        thumbView.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(progressTicker);
    }

    // Screen reader: every gesture is also exposed as a custom action.
    private static final int ACTION_LIKE = R.id.shorts_action_like;
    private static final int ACTION_COMMENTS = R.id.shorts_action_comments;
    private static final int ACTION_CHANNEL = R.id.shorts_action_channel;
    private static final int ACTION_SHARE = R.id.shorts_action_share;
    private static final int ACTION_NEXT = R.id.shorts_action_next;
    private static final int ACTION_PREVIOUS = R.id.shorts_action_previous;

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (item == null) {
            return;
        }
        final CharSequence title = titleView.getText();
        final String caption = item.getCaption();
        info.setContentDescription(LocaleController.formatString(R.string.ShortsAccDescr, title, caption != null ? caption : "",
                AndroidUtilities.formatShortDuration((int) item.getDuration())));
        info.setClickable(true);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                LocaleController.getString(userPaused ? R.string.AccActionPlay : R.string.AccActionPause)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_LIKE, LocaleController.getString(item.liked ? R.string.ShortsActionUnlike : R.string.ShortsActionLike)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_COMMENTS, LocaleController.getString(R.string.ShortsActionComments)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_CHANNEL, LocaleController.getString(R.string.ShortsActionChannel)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_SHARE, LocaleController.getString(R.string.ShortsShare)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_NEXT, LocaleController.getString(R.string.ShortsActionNext)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_PREVIOUS, LocaleController.getString(R.string.ShortsActionPrevious)));
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (item != null) {
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                delegate.onTogglePlay(this);
                return true;
            } else if (action == ACTION_LIKE) {
                delegate.onToggleLike(this, false);
                return true;
            } else if (action == ACTION_COMMENTS) {
                delegate.onOpenComments(this);
                return true;
            } else if (action == ACTION_CHANNEL) {
                delegate.onOpenChannel(this);
                return true;
            } else if (action == ACTION_SHARE) {
                delegate.onShare(this);
                return true;
            } else if (action == ACTION_NEXT) {
                delegate.onMovePage(this, 1);
                return true;
            } else if (action == ACTION_PREVIOUS) {
                delegate.onMovePage(this, -1);
                return true;
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }

    private static final class ActionButton extends LinearLayout {
        private final ImageView icon;
        private final TextView count;

        ActionButton(Context context, int iconRes) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setBackground(org.telegram.ui.ActionBar.Theme.createSelectorDrawable(0x33ffffff, 2));
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(icon, LayoutHelper.createLinear(28, 28));
            count = text(context, 14, true);
            count.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(count, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 6, 0, 0, 0));
            setIcon(iconRes, Color.WHITE);
            priorityTouch(this);
        }

        void setIcon(int res, int color) {
            icon.setImageResource(res);
            icon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        }

        void setCount(int value) {
            count.setText(value > 0 ? LocaleController.formatShortNumber(value, null) : "");
            count.setVisibility(value > 0 ? VISIBLE : GONE);
        }
    }

    /** Segmented progress bar (one segment per ~5 s) with elapsed/total seconds; tap or drag to seek. */
    private final class TimelineView extends View {
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private long progressMs, durationMs;
        private boolean seeking;

        TimelineView(Context context) {
            super(context);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(dp(12));
            textPaint.setTypeface(AndroidUtilities.bold());
            textPaint.setShadowLayer(dp(2), 0, dp(1), 0x66000000);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setProgress(long progress, long duration) {
            if (seeking) {
                return;
            }
            progressMs = progress;
            durationMs = duration;
            invalidate();
        }

        private int segments() {
            final int seconds = (int) (durationMs / 1000);
            return Math.max(1, Math.min(12, (seconds + 4) / 5));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final String time = AndroidUtilities.formatShortDuration((int) (progressMs / 1000)) + " / " + AndroidUtilities.formatShortDuration((int) (durationMs / 1000));
            final float textWidth = textPaint.measureText(time);
            final float left = dp(12), right = getWidth() - dp(12) - textWidth - dp(10);
            final float barTop = dp(4), barBottom = dp(7);
            final int count = segments();
            final float gap = dp(3);
            final float segWidth = (right - left - gap * (count - 1)) / count;
            final float fraction = durationMs > 0 ? Math.min(1f, progressMs / (float) durationMs) : 0f;
            final float filledX = left + (right - left) * fraction;
            for (int i = 0; i < count; i++) {
                final float sl = left + i * (segWidth + gap), sr = sl + segWidth;
                rect.set(sl, barTop, sr, barBottom);
                barPaint.setColor(0x55ffffff);
                canvas.drawRoundRect(rect, dp(2), dp(2), barPaint);
                if (filledX > sl) {
                    rect.set(sl, barTop, Math.min(sr, filledX), barBottom);
                    barPaint.setColor(Color.WHITE);
                    canvas.drawRoundRect(rect, dp(2), dp(2), barPaint);
                }
            }
            canvas.drawText(time, getWidth() - dp(12) - textWidth, barBottom + dp(5), textPaint);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (durationMs <= 0) {
                return false;
            }
            final float left = dp(12), right = getWidth() - dp(12) - textPaint.measureText("00:00 / 00:00") - dp(10);
            final float fraction = Math.max(0f, Math.min(1f, (event.getX() - left) / (right - left)));
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    seeking = true;
                case MotionEvent.ACTION_MOVE:
                    progressMs = (long) (fraction * durationMs);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    seeking = false;
                    progressMs = (long) (fraction * durationMs);
                    delegate.onSeek(ShortsCell.this, progressMs);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    seeking = false;
                    return true;
            }
            return true;
        }
    }
}
