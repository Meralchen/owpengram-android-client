package org.telegram.ui.Components.Reels;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Saved profile Reels. The list is capped by the server cursor and opens the normal viewer. */
public final class ReelsSavedSheet extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    private final int account;
    private final Theme.ResourcesProvider provider;
    private final Utilities.Callback<ReelsItem> openItem;
    private final ArrayList<ReelsItem> items = new ArrayList<>();
    private final RecyclerView list;
    private final Adapter adapter = new Adapter();
    private final TextView state;
    private final TextView title;
    private String cursor;
    private boolean loading;
    private boolean endReached;
    private boolean closed;

    public ReelsSavedSheet(Context context, int account, @Nullable Theme.ResourcesProvider provider,
                           Utilities.Callback<ReelsItem> openItem) {
        super(context, true, provider);
        this.account = account;
        this.provider = provider;
        this.openItem = openItem;
        setApplyBottomPadding(false);
        setApplyTopPadding(false);
        fixNavigationBar(ReelsTheme.sheetBackground(provider));

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        final View handle = new View(context);
        handle.setBackground(ReelsTheme.rounded(ReelsTheme.divider(provider), 2));
        root.addView(handle, LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 2));

        final LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(context);
        title.setText(LocaleController.getString(R.string.ShortsSaved));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        header.addView(title, LayoutHelper.createLinear(0, 52, 1f, 20, 0, 8, 0));

        final ImageView close = new ImageView(context);
        close.setImageResource(R.drawable.msg_close);
        close.setColorFilter(ReelsTheme.sheetText(provider));
        close.setContentDescription(LocaleController.getString(R.string.Close));
        close.setPadding(dp(10), dp(10), dp(10), dp(10));
        close.setOnClickListener(v -> dismiss());
        header.addView(close, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        list = new RecyclerView(context);
        final LinearLayoutManager manager = new LinearLayoutManager(context);
        list.setLayoutManager(manager);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (manager.findLastVisibleItemPosition() >= items.size() - 4) loadMore();
            }
        });
        root.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                (int) (AndroidUtilities.displaySize.y * 0.62f / AndroidUtilities.density)));

        state = new TextView(context);
        state.setGravity(Gravity.CENTER);
        state.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        root.addView(state, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));

        setCustomView(root);
        applyTheme();
        updateState();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        loadMore();
    }

    private void applyTheme() {
        setBackgroundColor(ReelsTheme.sheetBackground(provider));
        fixNavigationBar(ReelsTheme.sheetBackground(provider));
        title.setTextColor(ReelsTheme.sheetText(provider));
        state.setTextColor(ReelsTheme.textSecondary(provider));
    }

    private void loadMore() {
        if (loading || endReached) return;
        loading = true;
        updateState();
        String path = "/api/reels/saved?limit=20";
        if (!TextUtils.isEmpty(cursor)) {
            try {
                path += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {
            }
        }
        ReelsBackendApi.request(account, "GET", path, null, null, true, (body, status) -> {
            if (closed) return;
            loading = false;
            if (status >= 200 && status < 300 && body != null) {
                try {
                    final JSONObject object = new JSONObject(body);
                    final JSONArray values = object.optJSONArray("items");
                    if (values != null) {
                        for (int i = 0; i < values.length(); i++) {
                            final ReelsItem item = ReelsItem.fromProfile(account, values.optJSONObject(i));
                            if (item != null) items.add(item);
                        }
                    }
                    cursor = object.isNull("nextCursor") ? null : object.optString("nextCursor", null);
                    endReached = cursor == null;
                    adapter.notifyDataSetChanged();
                } catch (JSONException ignored) {
                }
            } else {
                endReached = true;
            }
            updateState();
        });
    }

    private void updateState() {
        if (loading && items.isEmpty()) {
            state.setVisibility(View.VISIBLE);
            state.setText(LocaleController.getString(R.string.Loading));
        } else if (!loading && items.isEmpty()) {
            state.setVisibility(View.VISIBLE);
            state.setText(LocaleController.getString(R.string.ShortsSavedEmpty));
        } else {
            state.setVisibility(View.GONE);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            applyTheme();
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public void dismiss() {
        if (closed) return;
        closed = true;
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        super.dismiss();
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(8), dp(16), dp(8));

            final BackupImageView thumb = new BackupImageView(parent.getContext());
            thumb.setRoundRadius(dp(8));
            row.addView(thumb, LayoutHelper.createLinear(56, 76));

            final LinearLayout text = new LinearLayout(parent.getContext());
            text.setOrientation(LinearLayout.VERTICAL);
            final TextView creator = new TextView(parent.getContext());
            creator.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            creator.setTypeface(AndroidUtilities.bold());
            creator.setSingleLine(true);
            text.addView(creator);
            final TextView caption = new TextView(parent.getContext());
            caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            caption.setMaxLines(2);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(caption, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
            return new RecyclerView.ViewHolder(row) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final LinearLayout row = (LinearLayout) holder.itemView;
            final BackupImageView thumb = (BackupImageView) row.getChildAt(0);
            final LinearLayout text = (LinearLayout) row.getChildAt(1);
            final TextView creator = (TextView) text.getChildAt(0);
            final TextView caption = (TextView) text.getChildAt(1);
            final ReelsItem item = items.get(position);

            creator.setTextColor(ReelsTheme.sheetText(provider));
            caption.setTextColor(ReelsTheme.textSecondary(provider));
            creator.setText(!TextUtils.isEmpty(item.creatorName) ? item.creatorName
                    : !TextUtils.isEmpty(item.creatorUsername) ? "@" + item.creatorUsername : "");
            caption.setText(item.getCaption() != null ? item.getCaption() : "");
            thumb.clearImage();
            if (!TextUtils.isEmpty(item.thumbnailUrl)) {
                thumb.setImage(item.thumbnailUrl, "64_88", (android.graphics.drawable.Drawable) null);
            }
            row.setOnClickListener(v -> {
                dismiss();
                if (openItem != null) openItem.run(item);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
