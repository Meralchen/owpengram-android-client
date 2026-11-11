package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;

/** Header above the profile grid. Shows Reels, Followers and Following together. */
public final class ReelsCreatorHeader extends LinearLayout {

    public interface Delegate {
        void onFollow();

        void onUpload();

        void onOpenProfile();
    }

    private final int account;
    private final Theme.ResourcesProvider provider;
    private final Delegate delegate;
    private final ImageView avatar;
    private final TextView name;
    private final TextView username;
    private final TextView stats;
    private final TextView action;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private boolean following;
    private boolean self;
    private String currentUsername;

    public ReelsCreatorHeader(Context context, int account, Theme.ResourcesProvider provider, Delegate delegate) {
        super(context);
        this.account = account;
        this.provider = provider;
        this.delegate = delegate;
        setOrientation(VERTICAL);
        setPadding(dp(18), dp(14), dp(18), dp(12));
        setBackgroundColor(ReelsTheme.background(provider));

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row);

        avatar = new ImageView(context);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setOnClickListener(v -> delegate.onOpenProfile());
        row.addView(avatar, new LayoutParams(dp(64), dp(64)));

        final LinearLayout identity = new LinearLayout(context);
        identity.setOrientation(VERTICAL);
        identity.setPadding(dp(12), 0, dp(8), 0);
        row.addView(identity, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        name = new TextView(context);
        name.setTextColor(ReelsTheme.textPrimary(provider));
        name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(name);

        username = new TextView(context);
        username.setTextColor(ReelsTheme.textSecondary(provider));
        username.setTextSize(13);
        username.setSingleLine(true);
        username.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(username);

        stats = new TextView(context);
        stats.setTextColor(ReelsTheme.textPrimary(provider));
        stats.setTextSize(12);
        stats.setPadding(0, dp(5), 0, 0);
        identity.addView(stats);

        action = new TextView(context);
        action.setTextSize(12);
        action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(15), 0, dp(15), 0);
        action.setOnClickListener(v -> {
            if (self) delegate.onUpload();
            else delegate.onFollow();
        });
        row.addView(action, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(34)));
    }

    public void bind(ReelsRepository.CreatorInfo info, String fallbackUsername) {
        currentUsername = info != null && !TextUtils.isEmpty(info.username) ? info.username : fallbackUsername;
        self = info != null && info.isSelf;
        following = info != null && info.isFollowing;
        final String display = info == null || TextUtils.isEmpty(info.displayName)
                ? (fallbackUsername == null ? "Reels" : fallbackUsername) : info.displayName;
        name.setText(display);
        username.setText(TextUtils.isEmpty(currentUsername) ? "" : "@" + currentUsername);
        stats.setText((info == null ? 0 : info.reels) + " Reels · "
                + (info == null ? 0 : info.followers) + " Followers · "
                + (info == null ? 0 : info.following) + " Following");

        final TLRPC.User user = new TLRPC.TL_user();
        user.id = info == null ? 0 : info.userId;
        user.first_name = display;
        user.username = currentUsername;
        avatarDrawable.setInfo(account, user);
        avatar.setImageDrawable(avatarDrawable);

        action.setVisibility(View.VISIBLE);
        if (self) {
            action.setText(LocaleController.getString(R.string.ReelsUpload));
            action.setTextColor(ReelsTheme.accentIcon(provider));
            action.setBackground(ReelsTheme.rounded(ReelsTheme.accent(provider), dp(17)));
        } else {
            action.setText(LocaleController.getString(following ? R.string.ReelsFollowing : R.string.ReelsFollow));
            action.setTextColor(following ? ReelsTheme.textPrimary(provider) : ReelsTheme.accentIcon(provider));
            action.setBackground(ReelsTheme.rounded(following ? 0x22000000 : ReelsTheme.accent(provider), dp(17)));
        }
        action.setContentDescription(self ? "Upload a Reel" : (following ? "Unfollow creator" : "Follow creator"));
    }

    public void setFollowing(boolean value, int followers, int followingCount) {
        following = value;
        action.setText(LocaleController.getString(value ? R.string.ReelsFollowing : R.string.ReelsFollow));
        action.setTextColor(value ? ReelsTheme.textPrimary(provider) : ReelsTheme.accentIcon(provider));
        action.setBackground(ReelsTheme.rounded(value ? 0x22000000 : ReelsTheme.accent(provider), dp(17)));
        final String[] parts = stats.getText().toString().split(" · ");
        if (parts.length == 3) {
            final String reels = parts[0].replace(" Reels", "").trim();
            stats.setText(reels + " Reels · " + followers + " Followers · " + followingCount + " Following");
        }
    }

    public boolean isFollowing() {
        return following;
    }

    public void setStats(int reels) {
        final String[] parts = stats.getText().toString().split(" · ");
        if (parts.length == 3) {
            stats.setText(reels + parts[1] + " · " + parts[2]);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
