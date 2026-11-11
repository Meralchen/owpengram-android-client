package org.telegram.ui.Components.Reels;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

/** Compact creator header above the Reels profile grid: avatar, name, stats and follow pill. */
public final class ReelsCreatorHeader extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private final int account;
    private final String username;
    private final boolean ownProfile;
    private final Theme.ResourcesProvider provider;
    private final Runnable onUpload;
    private final ReelsRepository repository;
    private final BackupImageView avatarView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final TextView nameView;
    private final TextView statsView;
    private final TextView actionView;
    private boolean following;
    private int reelsCount;
    private boolean busy;
    private boolean observingTheme;

    public ReelsCreatorHeader(Context context, int account, String username, boolean ownProfile,
                              @Nullable Theme.ResourcesProvider provider, Runnable onUpload) {
        super(context);
        this.account = account;
        this.username = username;
        this.ownProfile = ownProfile;
        this.provider = provider;
        this.onUpload = onUpload;
        this.repository = new ReelsRepository(account);

        setPadding(dp(12), dp(8), dp(12), dp(8));

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(20));
        addView(avatarView, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        final LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);

        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(nameView);

        statsView = new TextView(context);
        statsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        statsView.setSingleLine(true);
        textColumn.addView(statsView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        addView(textColumn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 62, 0, 100, 0));

        actionView = new TextView(context);
        actionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        actionView.setTypeface(AndroidUtilities.bold());
        actionView.setGravity(Gravity.CENTER);
        actionView.setMinWidth(dp(84));
        actionView.setPadding(dp(14), 0, dp(14), 0);
        actionView.setMinimumHeight(dp(32));
        actionView.setOnClickListener(v -> onAction());
        addView(actionView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

        updateIdentity(null);
        updateAction();
        applyTheme();
        loadCreator();
    }

    private void loadCreator() {
        if (!ReelsRepository.isValidUsername(username)) {
            return;
        }
        repository.loadCreator(username, (info, error) -> {
            if (info != null) {
                updateIdentity(info);
            }
        });
    }

    private void updateIdentity(ReelsRepository.CreatorInfo info) {
        final String name = info != null && !TextUtils.isEmpty(info.displayName) ? info.displayName : username;
        nameView.setText(TextUtils.isEmpty(name) ? "" : name);
        if (info != null) {
            following = info.isFollowing;
            reelsCount = info.reels;
            statsView.setText(LocaleController.formatString(R.string.ShortsCreatorStats,
                    String.valueOf(info.reels), String.valueOf(info.followers)));
            statsView.setVisibility(View.VISIBLE);
        } else {
            statsView.setText(!TextUtils.isEmpty(username) ? "@" + username : "");
        }

        final TLRPC.User user = TextUtils.isEmpty(username)
                ? null : MessagesController.getInstance(account).getUser(username);
        if (user != null) {
            avatarDrawable.setInfo(account, user);
            avatarView.setForUserOrChat(user, avatarDrawable);
        } else {
            final TLRPC.TL_user placeholder = new TLRPC.TL_user();
            placeholder.id = username != null ? Math.abs(username.hashCode()) : 0;
            placeholder.first_name = name;
            avatarDrawable.setInfo(account, placeholder);
            avatarView.setImageDrawable(avatarDrawable);
        }
        updateAction();
    }

    private void onAction() {
        if (ownProfile) {
            if (onUpload != null) onUpload.run();
            return;
        }
        if (busy || !ReelsRepository.isValidUsername(username)) return;
        busy = true;
        final boolean target = !following;
        ReelsRepository.setFollowing(account, username, target, (isFollowing, followers, followingCount, error) -> {
            busy = false;
            if (error == null) {
                following = isFollowing;
                if (followers > 0) {
                    statsView.setText(LocaleController.formatString(R.string.ShortsCreatorStats,
                            String.valueOf(reelsCount), String.valueOf(followers)));
                }
                updateAction();
            }
        });
    }

    private void updateAction() {
        if (ownProfile) {
            actionView.setText(LocaleController.getString(R.string.ShortsNewReel));
            actionView.setTextColor(ReelsTheme.accentText(provider));
            actionView.setBackground(ReelsTheme.rounded(ReelsTheme.accent(provider), 16));
            actionView.setContentDescription(LocaleController.getString(R.string.ShortsUploadReel));
            return;
        }
        actionView.setText(LocaleController.getString(following ? R.string.ShortsFollowingState : R.string.ShortsFollow));
        if (following) {
            final GradientDrawable background = ReelsTheme.rounded(0x00000000, 16);
            background.setStroke(dp(1), ReelsTheme.divider(provider));
            actionView.setBackground(background);
            actionView.setTextColor(ReelsTheme.textPrimary(provider));
        } else {
            actionView.setBackground(ReelsTheme.rounded(ReelsTheme.accent(provider), 16));
            actionView.setTextColor(ReelsTheme.accentText(provider));
        }
        actionView.setContentDescription(actionView.getText());
    }

    private void applyTheme() {
        nameView.setTextColor(ReelsTheme.textPrimary(provider));
        statsView.setTextColor(ReelsTheme.textSecondary(provider));
        updateAction();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!observingTheme) {
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
            observingTheme = true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (observingTheme) {
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
            observingTheme = false;
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            applyTheme();
        }
    }
}
