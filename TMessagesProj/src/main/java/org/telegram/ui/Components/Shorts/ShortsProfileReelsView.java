package org.telegram.ui.Components.Shorts;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Public profile grid that preserves REST-owned and channel-backed Reel sources. */
public final class ShortsProfileReelsView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    public interface Delegate {
        void onOpen(ShortsItem item);
        void onUpload();
    }

    private static final class Entry {
        String channelUsername;
        String caption;
        String shareUrl;
        int messageId;
        ShortsItem item;
    }

    private final int account;
    private final String username;
    private final boolean ownProfile;
    private final Delegate delegate;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final RecyclerView grid;
    private final TextView stateView;
    private final TextView uploadButton;
    private final Adapter adapter = new Adapter();
    private int nextOffset;
    private boolean endReached;
    private boolean loading;
    private boolean failed;
    private boolean observingTheme;
    private int requestGeneration;

    public ShortsProfileReelsView(Context context, int account, String username, boolean ownProfile, Delegate delegate) {
        super(context);
        this.account = account;
        this.username = username;
        this.ownProfile = ownProfile;
        this.delegate = delegate;
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        grid = new RecyclerView(context);
        grid.setLayoutManager(new GridLayoutManager(context, 3));
        grid.setAdapter(adapter);
        grid.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                GridLayoutManager manager = (GridLayoutManager) recyclerView.getLayoutManager();
                if (manager != null && manager.findLastVisibleItemPosition() >= entries.size() - 4) loadNextPage();
            }
        });
        addView(grid, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        stateView = new TextView(context);
        stateView.setGravity(Gravity.CENTER);
        stateView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        stateView.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        addView(stateView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        uploadButton = new TextView(context);
        uploadButton.setText(LocaleController.getString(R.string.ShortsUploadReel));
        uploadButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        uploadButton.setGravity(Gravity.CENTER);
        uploadButton.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(10), AndroidUtilities.dp(18), AndroidUtilities.dp(10));
        uploadButton.setOnClickListener(v -> delegate.onUpload());
        addView(uploadButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 8, 12, 0));
        updateThemeColors();
        updateState();
        if (!TextUtils.isEmpty(username)) loadNextPage(); else showEmpty();
    }

    public void updateThemeColors() {
        final int surface = Theme.getColor(Theme.key_windowBackgroundWhite);
        setBackgroundColor(surface);
        grid.setBackgroundColor(surface);
        stateView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        uploadButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4), 0.12f));
        bg.setCornerRadius(AndroidUtilities.dp(20));
        uploadButton.setBackground(bg);
        adapter.notifyDataSetChanged();
    }

    private String endpoint(int offset) {
        String base = MessagesController.getInstance(account).linkPrefix;
        if (TextUtils.isEmpty(base)) return null;
        base = base.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.startsWith("https://") && !base.startsWith("http://")) base = "https://" + base;
        try {
            return base + "/api/profiles/" + URLEncoder.encode(username, StandardCharsets.UTF_8.name()) + "/reels?limit=20&offset=" + offset;
        } catch (Exception e) {
            return null;
        }
    }

    private void loadNextPage() {
        if (loading || endReached || TextUtils.isEmpty(username)) return;
        final String url = endpoint(nextOffset);
        if (url == null) { showError(); return; }
        loading = true;
        failed = false;
        final int generation = requestGeneration;
        updateState();
        ShortsBackendApi.request(account, "GET", endpointPath(nextOffset), null, null, false, (body, status) -> {
            if (generation != requestGeneration) return;
            loading = false;
            if (body == null || status < 200 || status >= 300) { showError(); return; }
            try {
                JSONObject response = new JSONObject(body);
                JSONArray page = response.optJSONArray("items");
                if (page == null) throw new IllegalArgumentException("Missing items");
                final int first = entries.size();
                for (int i = 0; i < page.length(); i++) {
                    JSONObject object = page.optJSONObject(i);
                    if (object == null) continue;
                    Entry entry = new Entry();
                    entry.caption = object.optString("caption", "");
                    entry.shareUrl = object.optString("shareUrl", "");
                    if ("profile".equals(object.optString("source", ""))) {
                        entry.item = ShortsItem.fromProfile(account, object);
                        if (entry.item == null) continue;
                        if (!TextUtils.isEmpty(entry.shareUrl)) entry.item.publicShareUrl = entry.shareUrl;
                    } else {
                        JSONObject channel = object.optJSONObject("channel");
                        entry.channelUsername = object.optString("channelUsername",
                                channel != null ? channel.optString("username", "") : "");
                        entry.messageId = object.optInt("messageId", object.optInt("message_id", 0));
                        if (!ShortsRepository.isValidUsername(entry.channelUsername) || entry.messageId <= 0) continue;
                    }
                    entries.add(entry);
                }
                int newOffset = response.isNull("nextOffset") ? -1 : response.optInt("nextOffset", -1);
                endReached = newOffset < 0 || newOffset <= nextOffset;
                if (!endReached) nextOffset = newOffset;
                adapter.notifyItemRangeInserted(first, entries.size() - first);
                updateState();
                for (int i = first; i < entries.size(); i++) {
                    if (entries.get(i).item == null) resolveEntry(entries.get(i), i);
                }
                if (entries.isEmpty() && endReached) showEmpty();
                else if (entries.isEmpty()) loadNextPage();
            } catch (Exception e) {
                showError();
            }
        });
    }

    private String endpointPath(int offset) {
        final String url = endpoint(offset);
        final String base = ShortsBackendApi.baseUrl(account);
        return url != null && base != null ? url.substring(base.length()) : null;
    }

    private void resolveEntry(Entry entry, int position) {
        ShortsRepository.resolveLink(account, entry.channelUsername, entry.messageId, (item, failure) -> {
            if (position < entries.size() && entries.get(position) == entry) {
                entry.item = item;
                if (item != null) item.publicShareUrl = entry.shareUrl;
                adapter.notifyItemChanged(position);
            }
        });
    }

    private void showEmpty() {
        loading = false;
        failed = false;
        endReached = true;
        updateState();
    }

    public void reload() {
        requestGeneration++;
        entries.clear();
        nextOffset = 0;
        endReached = false;
        loading = false;
        failed = false;
        adapter.notifyDataSetChanged();
        updateState();
        loadNextPage();
    }

    private void showError() {
        loading = false;
        failed = true;
        updateState();
    }

    private void updateState() {
        final boolean empty = entries.isEmpty();
        stateView.setVisibility(empty || failed ? VISIBLE : GONE);
        if (failed) {
            stateView.setText(R.string.ShortsLoadFailed);
            stateView.setOnClickListener(v -> loadNextPage());
        } else if (empty && loading) {
            stateView.setText(R.string.Loading);
            stateView.setOnClickListener(null);
        } else if (empty) {
            stateView.setText(R.string.ShortsProfileEmpty);
            stateView.setOnClickListener(ownProfile ? v -> delegate.onUpload() : null);
        } else {
            stateView.setOnClickListener(null);
        }
        uploadButton.setVisibility(ownProfile ? VISIBLE : GONE);
        grid.setVisibility(entries.isEmpty() ? GONE : VISIBLE);
    }

    public void scrollToTop() {
        if (grid != null) grid.smoothScrollToPosition(0);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!observingTheme) {
            observingTheme = true;
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (observingTheme) {
            observingTheme = false;
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        }
        super.onDetachedFromWindow();
    }

    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) updateThemeColors();
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) { return new Holder(new Tile(parent.getContext())); }
        @Override public void onBindViewHolder(Holder holder, int position) { holder.tile.bind(entries.get(position)); }
        @Override public int getItemCount() { return entries.size(); }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final Tile tile;
        Holder(Tile view) { super(view); tile = view; }
    }

    private final class Tile extends FrameLayout {
        final BackupImageView image;
        final TextView caption;
        Entry entry;
        Tile(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            image = new BackupImageView(context);
            addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            caption = new TextView(context);
            caption.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            caption.setMaxLines(2);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setPadding(AndroidUtilities.dp(5), AndroidUtilities.dp(3), AndroidUtilities.dp(5), AndroidUtilities.dp(3));
            addView(caption, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
            setOnClickListener(v -> { if (entry != null && entry.item != null) delegate.onOpen(entry.item); });
        }
        void bind(Entry entry) {
            this.entry = entry;
            caption.setText(TextUtils.isEmpty(entry.caption) ? "▶" : entry.caption);
            caption.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            caption.setBackgroundColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhite), 0.82f));
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            if (entry.item != null && entry.item.source == ShortsItem.Source.PROFILE) {
                if (!TextUtils.isEmpty(entry.item.thumbnailUrl)) {
                    image.setImage(ImageLocation.getForPath(entry.item.thumbnailUrl), "320_320", (android.graphics.drawable.Drawable) null, null);
                } else {
                    image.clearImage();
                }
            } else if (entry.item != null && entry.item.document != null && !entry.item.document.thumbs.isEmpty()) {
                TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(entry.item.document.thumbs, 512);
                if (thumb != null) image.setImage(ImageLocation.getForDocument(thumb, entry.item.document), null, (android.graphics.drawable.Drawable) null, entry.item.messageObject);
                else image.clearImage();
            } else {
                image.clearImage();
            }
        }
    }
}
