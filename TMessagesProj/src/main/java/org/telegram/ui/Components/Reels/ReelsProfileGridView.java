package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/** 3-column profile Reels grid. Cells are public covers and open a profile-bounded viewer. */
public final class ReelsProfileGridView extends FrameLayout {

    public interface Delegate {
        void onOpen(ReelsItem item);

        void onUpload();
    }

    private final int account;
    private final String username;
    private final Theme.ResourcesProvider provider;
    private final Delegate delegate;
    private final ReelsRepository repository;
    private final ScrollView scrollView;
    private final LinearLayout content;
    private final ReelsCreatorHeader header;
    private final GridLayout grid;
    private final ArrayList<ReelsItem> items = new ArrayList<>();
    private TextView footer;
    private boolean loading;
    private boolean endReached;

    public ReelsProfileGridView(Context context, int account, String username,
                                Theme.ResourcesProvider provider, Delegate delegate) {
        super(context);
        this.account = account;
        this.username = username;
        this.provider = provider;
        this.delegate = delegate;
        this.repository = new ReelsRepository(account);
        setBackgroundColor(ReelsTheme.background(provider));

        scrollView = new ScrollView(context);
        addView(scrollView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        header = new ReelsCreatorHeader(context, account, provider, new ReelsCreatorHeader.Delegate() {
            @Override
            public void onFollow() {
                toggleFollow();
            }

            @Override
            public void onUpload() {
                delegate.onUpload();
            }

            @Override
            public void onOpenProfile() {
                // The grid's header is already the creator profile.
            }
        });
        content.addView(header);

        grid = new GridLayout(context);
        grid.setColumnCount(3);
        grid.setPadding(dp(2), dp(4), dp(2), dp(4));
        content.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        footer = new TextView(context);
        footer.setTextColor(ReelsTheme.textSecondary(provider));
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(14), 0, dp(18));
        content.addView(footer);

        scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (!v.canScrollVertically(1)) loadNext();
        });

        repository.setSource(ReelsFeedSource.PROFILE, username);
        header.bind(null, username);
        loadCreator();
        loadNext();
    }

    public void refresh() {
        items.clear();
        grid.removeAllViews();
        grid.removeAllViewsInLayout();
        endReached = false;
        loading = false;
        footer.setText("");
        repository.setSource(ReelsFeedSource.PROFILE, username);
        loadCreator();
        loadNext();
    }

    private void loadCreator() {
        repository.loadCreator(username, (creator, error) -> {
            if (creator != null) {
                header.bind(creator, username);
            }
        });
    }

    private void loadNext() {
        if (loading || endReached) return;
        loading = true;
        if (items.isEmpty()) footer.setText(LocaleController.getString(R.string.ReelsLoading));
        repository.loadNextPage((pageItems, end, error) -> {
            loading = false;
            if (error != null) {
                if (items.isEmpty()) {
                    footer.setText(error.describe() + "\nTap to retry");
                    footer.setOnClickListener(v -> {
                        footer.setOnClickListener(null);
                        loadNext();
                    });
                } else {
                    footer.setText("Couldn’t load more. Tap to retry.");
                    footer.setOnClickListener(v -> {
                        footer.setOnClickListener(null);
                        loadNext();
                    });
                }
                return;
            }
            footer.setOnClickListener(null);
            if (pageItems != null) {
                for (ReelsItem item : pageItems) {
                    items.add(item);
                    addCell(item);
                }
            }
            endReached = end;
            if (items.isEmpty() && endReached) {
                footer.setText(LocaleController.getString(R.string.ReelsNoReels));
            } else if (endReached) {
                footer.setText("");
            } else {
                footer.setText(LocaleController.getString(R.string.ReelsLoading));
            }
        });
    }

    private void addCell(ReelsItem item) {
        final int screenWidth = getResources().getDisplayMetrics().widthPixels;
        final int cellWidth = Math.max(1, (screenWidth - dp(4)) / 3);
        final int cellHeight = Math.round(cellWidth * 16f / 9f);
        final ReelsCoverView cover = new ReelsCoverView(getContext());
        cover.setCover(item.thumbnailUrl, item.initial());
        cover.setContentDescription("Open Reel by @" + item.ownerUsername);
        cover.setOnClickListener(v -> delegate.onOpen(item));
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = cellWidth;
        params.height = cellHeight;
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        grid.addView(cover, params);
    }

    private void toggleFollow() {
        final boolean next = !header.isFollowing();
        repository.setFollowing(username, next, (result, error) -> {
            if (error == null && result != null) {
                header.setFollowing(result.following, result.followers, result.followingCount);
            }
        });
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
