package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.tgnet.TLRPC;

/** Story header: identity and creator actions, never a channel title bar. */
public final class ReelsStoryHeader extends LinearLayout {

    public interface Delegate {
        void onClose();

        void onMute();

        void onFollow();

        void onOpenProfile();

        void onMore();
    }

    private final int account;
    private final Delegate delegate;
    private final ImageView avatarView;
    private final TextView nameView;
    private final TextView usernameView;
    private final TextView timeView;
    private final TextView followView;
    private final ImageView muteView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private boolean following;
    private boolean muted;
    private boolean own;

    public ReelsStoryHeader(Context context, int account, Delegate delegate) {
        super(context);
        this.account = account;
        this.delegate = delegate;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(12), dp(6), dp(6), dp(6));

        avatarView = new ImageView(context);
        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(avatarView, new LayoutParams(dp(34), dp(34)));

        final LinearLayout names = new LinearLayout(context);
        names.setOrientation(VERTICAL);
        names.setPadding(dp(8), 0, dp(6), 0);
        addView(names, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        nameView = new TextView(context);
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(14);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(nameView);

        usernameView = new TextView(context);
        usernameView.setTextColor(0xCCFFFFFF);
        usernameView.setTextSize(11);
        usernameView.setSingleLine(true);
        usernameView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(usernameView);

        timeView = new TextView(context);
        timeView.setTextColor(0x99FFFFFF);
        timeView.setTextSize(10);
        timeView.setSingleLine(true);
        names.addView(timeView);

        followView = new TextView(context);
        followView.setTextSize(12);
        followView.setTypeface(Typeface.DEFAULT_BOLD);
        followView.setGravity(Gravity.CENTER);
        followView.setPadding(dp(12), dp(5), dp(12), dp(5));
        followView.setOnClickListener(v -> {
            if (!own) delegate.onFollow();
        });
        addView(followView, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)));

        muteView = icon(context, R.drawable.msg_mute, "Mute Reel", v -> delegate.onMute());
        addView(muteView, iconParams());

        final ImageView moreView = icon(context, R.drawable.media_more, "More Reel actions", v -> delegate.onMore());
        addView(moreView, iconParams());

        final ImageView closeView = icon(context, R.drawable.msg_close, "Close Reels", v -> delegate.onClose());
        addView(closeView, iconParams());

        avatarView.setOnClickListener(v -> delegate.onOpenProfile());
        nameView.setOnClickListener(v -> delegate.onOpenProfile());
        usernameView.setOnClickListener(v -> delegate.onOpenProfile());
    }

    public void bind(ReelsItem item, boolean isOwn, boolean isMuted) {
        own = isOwn;
        final TLRPC.User user = new TLRPC.TL_user();
        user.id = item.ownerUserId;
        user.first_name = item.ownerDisplayName;
        user.username = item.ownerUsername;
        avatarDrawable.setInfo(account, user);
        avatarView.setImageDrawable(avatarDrawable);
        nameView.setText(item.ownerDisplayName);
        usernameView.setText(TextUtils.isEmpty(item.ownerUsername) ? "" : "@" + item.ownerUsername);
        if (item.createdAtMs > 0) {
            timeView.setVisibility(VISIBLE);
            timeView.setText(DateUtils.getRelativeTimeSpanString(item.createdAtMs,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        } else {
            timeView.setVisibility(GONE);
        }
        followView.setVisibility(own ? GONE : VISIBLE);
        setFollowing(false);
        setMuted(isMuted);
    }

    public void setFollowing(boolean value) {
        following = value;
        followView.setText(LocaleController.getString(value ? R.string.ReelsFollowing : R.string.ReelsFollow));
        followView.setContentDescription(LocaleController.getString(value ? R.string.ReelsFollowing : R.string.ReelsFollow));
        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(16));
        background.setColor(value ? 0x33FFFFFF : Color.WHITE);
        followView.setBackground(background);
        followView.setTextColor(value ? Color.WHITE : Color.BLACK);
        followView.setVisibility(own ? GONE : VISIBLE);
    }

    public boolean isFollowing() {
        return following;
    }

    public void setMuted(boolean value) {
        muted = value;
        muteView.setImageResource(value ? R.drawable.msg_unmute : R.drawable.msg_mute);
        muteView.setContentDescription(value ? "Unmute Reel" : "Mute Reel");
    }

    public boolean isMuted() {
        return muted;
    }

    private static ImageView icon(Context context, int resource, String description, View.OnClickListener listener) {
        final ImageView image = new ImageView(context);
        image.setImageResource(resource);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        image.setPadding(dpStatic(context, 6), dpStatic(context, 6), dpStatic(context, 6), dpStatic(context, 6));
        image.setContentDescription(description);
        image.setOnClickListener(listener);
        return image;
    }

    private LayoutParams iconParams() {
        final LayoutParams params = new LayoutParams(dp(34), dp(34));
        params.setMarginStart(dp(2));
        return params;
    }

    private int dp(float value) {
        return dpStatic(getContext(), value);
    }

    private static int dpStatic(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
