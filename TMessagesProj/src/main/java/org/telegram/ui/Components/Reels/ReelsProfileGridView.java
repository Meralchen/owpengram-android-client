package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Profile Reels tab: 3-column 9:16 grid with Telegram-theme chrome. Profile covers use the public
 * {@code thumbnailUrl}; rows without a cover show a gradient/initial placeholder, and opaque
 * channel items are resolved lazily without blocking the grid.
 */
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
    private final Theme.ResourcesProvider provider;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final RecyclerView grid;
    private final TextView stateView;
    private final ReelsCreatorHeader creatorHeader;
    private final Adapter adapter = new Adapter();
    private int nextOffset;
    private boolean endReached;
    private boolean loading;
    private boolean failed;
    private boolean observingTheme;
    private int requestGeneration;

    public ReelsProfileGridView(Context context, int account, String username, boolean ownProfile, Delegate delegate) {
        this(context, account, username, ownProfile, delegate, null);
    }

    public ReelsProfileGridView(Context context, int account, String username, boolean ownProfile, Delegate delegate,
                                @Nullable Theme.ResourcesProvider provider) {
        super(context);
        this.account = account;
        this.username = username;
        this.ownProfile = ownProfile;
        this.delegate = delegate;
        this.provider = provider;

        grid = new RecyclerView(context);
        grid.setLayoutManager(new GridLayoutManager(context, 3));
        grid.setAdapter(adapter);
        grid.setClipToPadding(false);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                GridLayoutManager manager = (GridLayoutManager) recyclerView.getLayoutManager();
                if (manager != null && manager.findLastVisibleItemPosition() >= entries.size() - 4) loadNextPage();
            }
        });
        addView(grid, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.TOP, 0, 60, 0, 0));

        stateView = new TextView(context);
        stateView.setGravity(Gravity.CENTER);
        stateView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        stateView.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        addView(stateView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        creatorHeader = new ReelsCreatorHeader(context, account, username, ownProfile, provider,
                () -> delegate.onUpload());
        creatorHeader.setVisibility(TextUtils.isEmpty(username) ? View.GONE : View.VISIBLE);
        addView(creatorHeader, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 60, Gravity.TOP));
        updateThemeColors();

        loadNextPage();
    }

    private void updateThemeColors() {
        final int bg = ReelsTheme.background(provider);
        setBackgroundColor(bg);
        grid.setBackgroundColor(bg);
        stateView.setTextColor(ReelsTheme.textSecondary(provider));
    }

    public void scrollToTop() {
        if (grid != null) grid.smoothScrollToPosition(0);
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
                    final JSONObject response = new JSONObject(body);
                    final JSONArray values = response.optJSONArray("items");
                    if (values != null) {
                        for (int i = 0; i < values.length(); i++) {
                            final JSONObject object = values.optJSONObject(i);
                            if (object == null) continue;
                            final Entry entry = new Entry();
                            entry.caption = object.optString("caption", "");
                            entry.shareUrl = object.optString("shareUrl", "");
                            final String source = object.optString("source", "");
                            if ("profile".equals(source) || object.has("mediaUrl")) {
                                entry.item = ReelsItem.fromProfile(account, object);
                                if (entry.item != null) entries.add(entry);
                                continue;
                            }
                            final JSONObject channel = object.optJSONObject("channel");
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

    private String initialFor(Entry entry) {
        String name = entry.item != null ? entry.item.creatorName : entry.channelUsername;
        if (TextUtils.isEmpty(name) && entry.item != null) name = entry.item.creatorUsername;
        if (TextUtils.isEmpty(name)) name = username;
        return TextUtils.isEmpty(name) ? "" : name.substring(0, 1).toUpperCase();
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemCount() {
            return entries.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final FrameLayout frame = new FrameLayout(parent.getContext()) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    final int width = MeasureSpec.getSize(widthMeasureSpec);
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (width * 16f / 9f), MeasureSpec.EXACTLY));
                }
            };
            frame.setPadding(AndroidUtilities.dp(ReelsTheme.gridGap), AndroidUtilities.dp(ReelsTheme.gridGap),
                    AndroidUtilities.dp(ReelsTheme.gridGap), AndroidUtilities.dp(ReelsTheme.gridGap));

            final TextView placeholder = new TextView(parent.getContext());
            placeholder.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
            placeholder.setTypeface(AndroidUtilities.bold());
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setTextColor(ReelsTheme.accent(provider));
            frame.addView(placeholder, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            final BackupImageView thumb = new BackupImageView(parent.getContext());
            thumb.setRoundRadius(AndroidUtilities.dp(ReelsTheme.gridRadius));
            thumb.getImageReceiver().setAspectFit(false);
            frame.addView(thumb, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            final ImageView play = new ImageView(parent.getContext());
            play.setImageResource(R.drawable.play_mini_video);
            play.setColorFilter(new PorterDuffColorFilter(ReelsTheme.viewerPrimary, PorterDuff.Mode.SRC_IN));
            play.setAlpha(0.9f);
            frame.addView(play, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

            final View shade = new View(parent.getContext());
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0x99000000, Color.TRANSPARENT}));
            frame.addView(shade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.BOTTOM));

            final TextView views = new TextView(parent.getContext());
            views.setTextColor(ReelsTheme.viewerPrimary);
            views.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            views.setTypeface(AndroidUtilities.bold());
            frame.addView(views, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.LEFT, 6, 0, 6, 5));
            return new RecyclerView.ViewHolder(frame) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final FrameLayout frame = (FrameLayout) holder.itemView;
            final TextView placeholder = (TextView) frame.getChildAt(0);
            final BackupImageView thumb = (BackupImageView) frame.getChildAt(1);
            final TextView views = (TextView) frame.getChildAt(4);
            final Entry entry = entries.get(position);
            final String initial = initialFor(entry);

            placeholder.setText(initial);
            final GradientDrawable placeholderBackground = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{withAlpha(ReelsTheme.accent(provider), 0x33), withAlpha(ReelsTheme.accent(provider), 0x14)});
            placeholderBackground.setCornerRadius(AndroidUtilities.dp(ReelsTheme.gridRadius));
            placeholder.setBackground(placeholderBackground);
            placeholder.setVisibility(View.VISIBLE);
            views.setText("");
            thumb.clearImage();

            if (entry.item != null && entry.item.source == ReelsItem.Source.PROFILE) {
                if (!TextUtils.isEmpty(entry.item.thumbnailUrl)) {
                    thumb.setImage(ImageLocation.getForPath(entry.item.thumbnailUrl), "160_284",
                            (android.graphics.drawable.Drawable) null, null);
                    placeholder.setVisibility(View.GONE);
                }
                if (entry.item.views > 0) views.setText(LocaleController.formatShortNumber(entry.item.views, null));
            } else if (entry.item != null && entry.item.document != null) {
                thumb.setImage(ImageLocation.getForDocument(
                                FileLoader.getClosestPhotoSizeWithSize(entry.item.document.thumbs, 320), entry.item.document),
                        "160_284", null, null, entry.item.messageObject);
                placeholder.setVisibility(View.GONE);
                if (entry.item.views > 0) views.setText(LocaleController.formatShortNumber(entry.item.views, null));
            } else {
                ensureResolved(entry, () -> notifyItemChanged(position));
            }
            frame.setOnClickListener(v -> ensureResolved(entry, () -> delegate.onOpen(entry.item)));
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
