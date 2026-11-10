package org.telegram.ui.Components.Reels;

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
public final class ReelsProfileGridView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    public interface Delegate {
        void onOpen(ReelsItem item);
        void onUpload();
    }

    private static final class Entry {
        String channelUsername;
        String caption;
        String shareUrl;
        int messageId;
        ReelsItem item;
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

    public ReelsProfileGridView(Context context, int account, String username, boolean ownProfile, Delegate delegate) {
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

        loadNextPage();
    }

    private void updateThemeColors() {
        int bg = Theme.getColor(Theme.key_windowBackgroundWhite);
        setBackgroundColor(bg);
        grid.setBackgroundColor(bg);
        stateView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        uploadButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        GradientDrawable uploadBg = new GradientDrawable();
        uploadBg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        uploadBg.setCornerRadius(AndroidUtilities.dp(8));
        uploadButton.setBackground(uploadBg);
        uploadButton.setVisibility(ownProfile ? View.VISIBLE : View.GONE);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!observingTheme) {
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
            observingTheme = true;
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (observingTheme) {
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
            observingTheme = false;
        }
    }

    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            updateThemeColors();
            adapter.notifyDataSetChanged();
        }
    }

    public void reload() {
        requestGeneration++;
        entries.clear();
        adapter.notifyDataSetChanged();
        nextOffset = 0;
        endReached = false;
        loading = false;
        failed = false;
        loadNextPage();
    }

    private String endpointPath(int offset) {
        try {
            return "/api/profiles/" + URLEncoder.encode(username, StandardCharsets.UTF_8.name()) + "/reels?limit=20&offset=" + offset;
        } catch (Exception e) {
            return "/api/profiles/" + username + "/reels?limit=20&offset=" + offset;
        }
    }

    public void loadNextPage() {
        if (loading || endReached || TextUtils.isEmpty(username)) return;
        loading = true;
        failed = false;
        updateStateText();
        final int generation = ++requestGeneration;
        final int requestOffset = nextOffset;
        ReelsBackendApi.request(account, "GET", endpointPath(nextOffset), null, null, false, (body, status) -> {
            if (generation != requestGeneration) return;
            loading = false;
            if (status >= 200 && status < 300 && body != null) {
                try {
                    JSONObject response = new JSONObject(body);
                    JSONArray values = response.optJSONArray("items");
                    if (values != null) {
                        for (int i = 0; i < values.length(); i++) {
                            JSONObject object = values.optJSONObject(i);
                            if (object == null) continue;
                            Entry entry = new Entry();
                            entry.caption = object.optString("caption", "");
                            entry.shareUrl = object.optString("shareUrl", "");
                            String source = object.optString("source", "");
                            if ("profile".equals(source) || object.has("mediaUrl")) {
                                entry.item = ReelsItem.fromProfile(account, object);
                                if (entry.item != null) entries.add(entry);
                                continue;
                            }
                            JSONObject channel = object.optJSONObject("channel");
                            entry.channelUsername = object.optString("channelUsername",
                                    channel != null ? channel.optString("username", "") : "");
                            entry.messageId = object.optInt("messageId", object.optInt("message_id", 0));
                            if (!ReelsRepository.isValidUsername(entry.channelUsername) || entry.messageId <= 0) continue;
                            entries.add(entry);
                        }
                    }
                    if (response.isNull("nextOffset")) endReached = true;
                    else nextOffset = response.optInt("nextOffset", requestOffset + (values != null ? values.length() : 0));
                    adapter.notifyDataSetChanged();
                } catch (Exception e) {
                    failed = true;
                }
            } else {
                failed = true;
            }
            updateStateText();
        });
    }

    private void ensureResolved(Entry entry, Runnable onResolved) {
        if (entry.item != null) {
            onResolved.run();
            return;
        }
        ReelsRepository.resolveLink(account, entry.channelUsername, entry.messageId, (item, failure) -> {
            if (item != null) {
                item.publicShareUrl = entry.shareUrl;
                entry.item = item;
                onResolved.run();
            }
        });
    }

    private void updateStateText() {
        if (loading && entries.isEmpty()) {
            stateView.setVisibility(View.VISIBLE);
            stateView.setText(LocaleController.getString(R.string.Loading));
            stateView.setOnClickListener(null);
        } else if (failed && entries.isEmpty()) {
            stateView.setVisibility(View.VISIBLE);
            stateView.setText(R.string.ShortsLoadFailed);
            stateView.setOnClickListener(v -> loadNextPage());
        } else if (entries.isEmpty()) {
            stateView.setVisibility(View.VISIBLE);
            stateView.setText(R.string.ShortsProfileEmpty);
            stateView.setOnClickListener(null);
        } else {
            stateView.setVisibility(View.GONE);
            stateView.setOnClickListener(null);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return entries.size(); }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout frame = new FrameLayout(parent.getContext()) {
                @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int w = MeasureSpec.getSize(widthMeasureSpec);
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (w * 16f / 9f), MeasureSpec.EXACTLY));
                }
            };
            frame.setPadding(AndroidUtilities.dp(1), AndroidUtilities.dp(1), AndroidUtilities.dp(1), AndroidUtilities.dp(1));
            BackupImageView thumb = new BackupImageView(parent.getContext());
            thumb.getImageReceiver().setAspectFit(false);
            frame.addView(thumb, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            View shade = new View(parent.getContext());
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x99000000, Color.TRANSPARENT}));
            frame.addView(shade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.BOTTOM));
            TextView views = new TextView(parent.getContext());
            views.setTextColor(Color.WHITE);
            views.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            frame.addView(views, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 6, 0, 6, 6));
            return new RecyclerView.ViewHolder(frame) {};
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            FrameLayout frame = (FrameLayout) holder.itemView;
            BackupImageView thumb = (BackupImageView) frame.getChildAt(0);
            TextView views = (TextView) frame.getChildAt(2);
            Entry entry = entries.get(position);
            views.setText("");
            if (entry.item != null && entry.item.source == ReelsItem.Source.PROFILE) {
                if (entry.item.thumbnailUrl != null) thumb.setImage(ImageLocation.getForPath(entry.item.thumbnailUrl), "120_213", null, null);
                else thumb.setImageDrawable(null);
                if (entry.item.views > 0) views.setText(LocaleController.formatShortNumber(entry.item.views, null));
            } else if (entry.item != null && entry.item.document != null) {
                thumb.setImage(ImageLocation.getForDocument(FileLoader.getClosestPhotoSizeWithSize(entry.item.document.thumbs, 320), entry.item.document), "120_213", null, null, entry.item.messageObject);
                if (entry.item.views > 0) views.setText(LocaleController.formatShortNumber(entry.item.views, null));
            } else {
                thumb.setImageDrawable(null);
                ensureResolved(entry, () -> notifyItemChanged(position));
            }
            frame.setOnClickListener(v -> ensureResolved(entry, () -> delegate.onOpen(entry.item)));
        }
    }
}
