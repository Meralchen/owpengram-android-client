package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/** Saved Reels, kept as a real paginated source rather than an empty fallback. */
public final class ReelsSavedSheet extends BottomSheet {

    public interface Listener {
        void onOpen(ReelsItem item);
    }

    private final ReelsRepository repository;
    private final ArrayList<ReelsItem> items = new ArrayList<>();
    private final LinearLayout content;
    private final Listener listener;
    private final Theme.ResourcesProvider provider;
    private boolean loading;
    private boolean endReached;

    public ReelsSavedSheet(Context context, int account, Theme.ResourcesProvider provider, Listener listener) {
        super(context, false, provider);
        this.provider = provider;
        this.listener = listener;
        this.repository = new ReelsRepository(account);
        repository.setSource(ReelsFeedSource.SAVED, null);
        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        setBackgroundColor(ReelsTheme.dialogBackground(provider));

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ReelsTheme.dialogBackground(provider));
        root.setMinimumHeight(dp(420));

        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(10), dp(10), dp(8));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.ReelsSaved));
        title.setTextColor(ReelsTheme.dialogText(provider));
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView close = new TextView(context);
        close.setText("×");
        close.setTextSize(25);
        close.setTextColor(ReelsTheme.dialogSecondary(provider));
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close saved Reels");
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));

        final ScrollView scroll = new ScrollView(context);
        final int scrollId = View.generateViewId();
        scroll.setId(scrollId);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), 0, dp(10), dp(12));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (!v.canScrollVertically(1)) loadNext();
        });

        setCustomView(root);
        loadNext();
    }

    private void loadNext() {
        if (loading || endReached) return;
        loading = true;
        if (items.isEmpty()) content.removeAllViews();
        final TextView loadingView = message("Loading saved Reels…");
        if (items.isEmpty()) content.addView(loadingView);
        repository.loadNextPage((pageItems, end, error) -> {
            loading = false;
            if (items.isEmpty()) content.removeAllViews();
            if (error != null) {
                if (items.isEmpty()) {
                    content.addView(message(error.describe()));
                    final TextView retry = button("Retry");
                    retry.setOnClickListener(v -> loadNext());
                    content.addView(retry);
                } else {
                    content.addView(button("Retry loading more"));
                }
                return;
            }
            if (pageItems != null) {
                for (ReelsItem item : pageItems) {
                    items.add(item);
                    content.addView(row(item));
                }
            }
            endReached = end;
            if (items.isEmpty() && endReached) {
                content.addView(message("No saved Reels yet."));
            } else if (!endReached) {
                final TextView more = button("Load more");
                more.setOnClickListener(v -> loadNext());
                content.addView(more);
            }
        });
    }

    private View row(ReelsItem item) {
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(6), dp(4), dp(6));

        final ReelsCoverView cover = new ReelsCoverView(getContext());
        cover.setCover(item.thumbnailUrl, item.initial());
        row.addView(cover, new LinearLayout.LayoutParams(dp(58), dp(78)));

        final LinearLayout text = new LinearLayout(getContext());
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView owner = new TextView(getContext());
        owner.setText(item.ownerDisplayName + " @" + item.ownerUsername);
        owner.setTextColor(ReelsTheme.dialogText(provider));
        owner.setTextSize(13);
        owner.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(owner);

        final TextView caption = new TextView(getContext());
        caption.setText(TextUtils.isEmpty(item.caption) ? "Reel" : item.caption);
        caption.setTextColor(ReelsTheme.dialogSecondary(provider));
        caption.setTextSize(12);
        caption.setMaxLines(2);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(caption);

        row.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onOpen(item);
        });
        return row;
    }

    private TextView message(String text) {
        final TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(ReelsTheme.dialogSecondary(provider));
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(24), 0, dp(12));
        return view;
    }

    private TextView button(String text) {
        final TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(ReelsTheme.accentIcon(provider));
        view.setTextSize(12);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setBackground(ReelsTheme.rounded(ReelsTheme.accent(provider), dp(16)));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(8);
        view.setLayoutParams(params);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
