package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

import java.util.Locale;

/** Caption, Story action row and reply composer. There is intentionally no right-side rail. */
public final class ReelsStoryActions extends LinearLayout {

    public interface Delegate {
        void onLike();

        void onComments();

        void onShare();

        void onSave();
    }

    private final Delegate delegate;
    private final TextView captionView;
    private final TextView likeView;
    private final TextView commentsView;
    private final TextView shareView;
    private final TextView saveView;
    private final TextView replyView;

    public ReelsStoryActions(Context context, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        setOrientation(VERTICAL);
        setPadding(dp(14), 0, dp(14), dp(10));

        captionView = new TextView(context);
        captionView.setTextColor(Color.WHITE);
        captionView.setTextSize(14);
        captionView.setMaxLines(3);
        captionView.setEllipsize(TextUtils.TruncateAt.END);
        captionView.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        addView(captionView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, dp(42)));

        likeView = action(context, R.drawable.media_like, "Like Reel", row);
        commentsView = action(context, R.drawable.menu_comments, "Comments", row);
        shareView = action(context, R.drawable.media_share, "Share Reel", row);
        saveView = action(context, R.drawable.msg_save_story, "Save Reel", row);

        likeView.setOnClickListener(v -> delegate.onLike());
        commentsView.setOnClickListener(v -> delegate.onComments());
        shareView.setOnClickListener(v -> delegate.onShare());
        saveView.setOnClickListener(v -> delegate.onSave());

        replyView = new TextView(context);
        replyView.setTextColor(0xE6FFFFFF);
        replyView.setTextSize(13);
        replyView.setGravity(Gravity.CENTER_VERTICAL);
        replyView.setSingleLine(true);
        replyView.setPadding(dp(15), 0, dp(15), 0);
        replyView.setBackground(ReelsTheme.rounded(0x33FFFFFF, dp(22)));
        replyView.setOnClickListener(v -> delegate.onComments());
        addView(replyView, new LayoutParams(LayoutParams.MATCH_PARENT, dp(42)));
    }

    public void bind(ReelsItem item, boolean own) {
        if (TextUtils.isEmpty(item.caption)) {
            captionView.setVisibility(GONE);
        } else {
            captionView.setVisibility(VISIBLE);
            captionView.setText(item.caption);
        }
        update(item);
        replyView.setText(own ? "Add a comment…" : "Reply to @" + item.ownerUsername + "…");
        replyView.setContentDescription("Open comments");
    }

    public void update(ReelsItem item) {
        if (item == null) return;
        likeView.setText(shortCount(item.likes));
        likeView.setCompoundDrawablesWithIntrinsicBounds(
                item.liked ? R.drawable.media_like_active : R.drawable.media_like, 0, 0, 0);
        likeView.setContentDescription(item.liked ? "Unlike Reel" : "Like Reel");
        commentsView.setText(shortCount(item.comments));
        shareView.setText(shortCount(item.shares));
        saveView.setText(shortCount(item.saves));
        saveView.setCompoundDrawablesWithIntrinsicBounds(
                item.saved ? R.drawable.msg_saved : R.drawable.msg_save_story, 0, 0, 0);
        saveView.setContentDescription(item.saved ? "Remove Reel from saved" : "Save Reel");
    }

    private static TextView action(Context context, int icon, String description, LinearLayout parent) {
        final TextView view = new TextView(context);
        view.setTextColor(Color.WHITE);
        view.setTextSize(11);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setSingleLine(true);
        view.setCompoundDrawablePadding(dpStatic(context, 5));
        view.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        view.setContentDescription(description);
        view.setPadding(0, 0, dpStatic(context, 18), 0);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        parent.addView(view, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        return view;
    }

    private static String shortCount(long count) {
        if (count >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1fB", count / 1_000_000_000d);
        }
        if (count >= 1_000_000L) {
            return String.format(Locale.US, "%.1fM", count / 1_000_000d);
        }
        if (count >= 1_000L) {
            return String.format(Locale.US, "%.1fK", count / 1_000d);
        }
        return String.valueOf(count);
    }

    private int dp(float value) {
        return dpStatic(getContext(), value);
    }

    private static int dpStatic(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
