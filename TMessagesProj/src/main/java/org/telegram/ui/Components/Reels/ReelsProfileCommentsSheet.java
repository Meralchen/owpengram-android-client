package org.telegram.ui.Components.Reels;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

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
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;

/** REST comments for profile-owned Reels, including replies, GIFs, likes, pin and delete. */
public final class ReelsProfileCommentsSheet extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    private static final int MAX_GIF_BYTES = 8 * 1024 * 1024;

    private static final class Comment {
        String id;
        String parentId;
        String author;
        String text;
        String gifUrl;
        String gifId;
        int likes;
        int replyCount;
        int depth;
        boolean liked;
        boolean pinned;
        boolean canDelete;
        boolean canPin;
        boolean repliesLoaded;
    }

    private final ReelsItem item;
    private final Theme.ResourcesProvider provider;
    private final Runnable onChanged;
    private final ArrayList<Comment> comments = new ArrayList<>();
    private final HashSet<String> loadingReplies = new HashSet<>();
    private final RecyclerView list;
    private final Adapter adapter = new Adapter();
    private final TextView state;
    private final TextView title;
    private final TextView send;
    private final TextView gifButton;
    private final TextView replyChip;
    private final EditTextBoldCursor input;
    private final View handle;
    private Comment replyingTo;
    private boolean loading;
    private boolean endReached;
    private boolean closed;
    private boolean sending;
    private boolean downloadingGif;
    private int totalCount;

    public ReelsProfileCommentsSheet(Context context, ReelsItem item) {
        this(context, item, null, null);
    }

    public ReelsProfileCommentsSheet(Context context, ReelsItem item, @Nullable Theme.ResourcesProvider provider) {
        this(context, item, provider, null);
    }

    public ReelsProfileCommentsSheet(Context context, ReelsItem item, @Nullable Theme.ResourcesProvider provider,
                                     @Nullable Runnable onChanged) {
        super(context, true, provider);
        this.item = item;
        this.provider = provider;
        this.onChanged = onChanged;
        currentAccount = item.account;
        totalCount = item.comments;
        setApplyBottomPadding(false);
        setApplyTopPadding(false);
        fixNavigationBar(ReelsTheme.sheetBackground(provider));

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        handle = new View(context);
        handle.setBackground(ReelsTheme.rounded(ReelsTheme.divider(provider), 2));
        root.addView(handle, LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 2));

        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        title = new TextView(context);
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
                if (manager.findLastVisibleItemPosition() >= comments.size() - 4) loadMore();
            }
        });
        root.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                (int) (AndroidUtilities.displaySize.y * 0.58f / AndroidUtilities.density)));

        state = new TextView(context);
        state.setGravity(Gravity.CENTER);
        state.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        root.addView(state, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));

        replyChip = new TextView(context);
        replyChip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        replyChip.setPadding(dp(20), dp(6), dp(20), dp(6));
        replyChip.setVisibility(View.GONE);
        replyChip.setOnClickListener(v -> clearReply());
        root.addView(replyChip, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final View divider = new View(context);
        divider.setBackgroundColor(ReelsTheme.divider(provider));
        root.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        final LinearLayout inputRow = new LinearLayout(context);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditTextBoldCursor(context);
        input.setHint(LocaleController.getString(R.string.LeaveAComment));
        input.setBackground(null);
        input.setMaxLines(4);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setPadding(dp(12), dp(12), dp(8), dp(12));
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendComment();
                return true;
            }
            return false;
        });
        inputRow.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        gifButton = new TextView(context);
        gifButton.setText(LocaleController.getString(R.string.ShortsGif));
        gifButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        gifButton.setTypeface(AndroidUtilities.bold());
        gifButton.setGravity(Gravity.CENTER);
        gifButton.setPadding(dp(8), dp(12), dp(8), dp(12));
        gifButton.setOnClickListener(v -> showGifDialog());
        inputRow.addView(gifButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        send = new TextView(context);
        send.setText(LocaleController.getString(R.string.Send));
        send.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        send.setTypeface(AndroidUtilities.bold());
        send.setGravity(Gravity.CENTER);
        send.setPadding(dp(12), dp(12), dp(20), dp(12));
        send.setOnClickListener(v -> sendComment());
        inputRow.addView(send, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        root.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        setCustomView(root);
        applyTheme();
        updateTitle();
        updateState();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        loadMore();
    }

    private void applyTheme() {
        setBackgroundColor(ReelsTheme.sheetBackground(provider));
        fixNavigationBar(ReelsTheme.sheetBackground(provider));
        title.setTextColor(ReelsTheme.sheetText(provider));
        state.setTextColor(ReelsTheme.textSecondary(provider));
        input.setTextColor(ReelsTheme.sheetText(provider));
        input.setHintTextColor(ReelsTheme.sheetHint(provider));
        send.setTextColor(ReelsTheme.sheetAccent(provider));
        gifButton.setTextColor(ReelsTheme.sheetAccent(provider));
        replyChip.setTextColor(ReelsTheme.sheetAccent(provider));
        if (handle != null) {
            handle.setBackground(ReelsTheme.rounded(ReelsTheme.divider(provider), 2));
        }
    }

    private void updateTitle() {
        final int count = totalCount;
        final String label = LocaleController.getString(R.string.ShortsComments);
        title.setText(count > 0 ? label + " (" + count + ")" : label);
    }

    private void updateState() {
        if (loading && comments.isEmpty()) {
            state.setVisibility(View.VISIBLE);
            state.setText(LocaleController.getString(R.string.Loading));
        } else if (!loading && comments.isEmpty()) {
            state.setVisibility(View.VISIBLE);
            state.setText(LocaleController.getString(R.string.NoComments));
        } else {
            state.setVisibility(View.GONE);
        }
    }

    private String commentsPath() {
        return "/api/reels/" + Uri.encode(item.reelId) + "/comments";
    }

    private String lastTopLevelId() {
        for (int i = comments.size() - 1; i >= 0; i--) {
            if (comments.get(i).depth == 0) return comments.get(i).id;
        }
        return null;
    }

    private void loadMore() {
        if (loading || endReached || item == null) return;
        loading = true;
        updateState();
        String path = commentsPath() + "?limit=30";
        final String before = lastTopLevelId();
        if (!TextUtils.isEmpty(before)) {
            try {
                path += "&before=" + URLEncoder.encode(before, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {
            }
        }
        ReelsBackendApi.request(currentAccount, "GET", path, null, null, true, (body, status) -> {
            if (closed) return;
            loading = false;
            if (status >= 200 && status < 300 && body != null) {
                try {
                    final JSONObject object = new JSONObject(body);
                    final JSONArray values = object.optJSONArray("items");
                    if (values != null) {
                        for (int i = 0; i < values.length(); i++) {
                            final JSONObject value = values.optJSONObject(i);
                            if (value == null) continue;
                            final Comment c = parseComment(value, 0, null);
                            if (c != null && findById(c.id) == null) comments.add(c);
                        }
                        endReached = values.length() < 30;
                    }
                    final JSONArray pinned = object.optJSONArray("pinned");
                    if (pinned != null) {
                        for (int i = pinned.length() - 1; i >= 0; i--) {
                            final JSONObject value = pinned.optJSONObject(i);
                            if (value == null) continue;
                            final Comment c = parseComment(value, 0, null);
                            if (c != null && findById(c.id) == null) {
                                c.pinned = true;
                                comments.add(0, c);
                            }
                        }
                    }
                    final int total = object.optInt("total", object.optInt("count", object.optInt("commentCount", -1)));
                    if (total >= 0) {
                        totalCount = total;
                        item.comments = total;
                        notifyChanged();
                    }
                    adapter.notifyDataSetChanged();
                    updateTitle();
                } catch (JSONException ignored) {
                }
            } else {
                endReached = true;
            }
            updateState();
        });
    }

    private Comment findById(String id) {
        if (id == null) return null;
        for (Comment c : comments) {
            if (id.equals(c.id)) return c;
        }
        return null;
    }

    private Comment parseComment(JSONObject value, int depth, String parentId) {
        if (value == null) return null;
        final String id = value.optString("id", "");
        String text = value.optString("text", "");
        String gifUrl = value.optString("gifUrl", value.optString("gif_url", ""));
        String gifId = value.optString("gifId", value.optString("gif_id", ""));
        final JSONObject gif = value.optJSONObject("gif");
        if (gif != null) {
            if (TextUtils.isEmpty(gifUrl)) gifUrl = gif.optString("url", "");
            if (TextUtils.isEmpty(gifId)) gifId = gif.optString("id", "");
        }
        if (TextUtils.isEmpty(id) || (TextUtils.isEmpty(text) && TextUtils.isEmpty(gifUrl))) return null;

        final Comment c = new Comment();
        c.id = id;
        c.parentId = parentId;
        c.depth = depth;
        c.text = text;
        c.gifUrl = gifUrl;
        c.gifId = gifId;
        c.author = value.optString("username", value.optString("author", ""));
        final JSONObject author = value.optJSONObject("author");
        if (author != null) {
            c.author = author.optString("username", author.optString("name", c.author));
        }
        c.likes = value.optInt("likes", value.optInt("likeCount", 0));
        c.liked = value.optBoolean("liked", value.optBoolean("isLiked", false));
        c.pinned = value.optBoolean("pinned", value.optBoolean("isPinned", false));
        c.replyCount = value.optInt("replyCount", value.optInt("replies", 0));
        final JSONArray replies = value.optJSONArray("replies");
        if (replies != null) c.replyCount = Math.max(c.replyCount, replies.length());
        c.canDelete = value.optBoolean("canDelete", value.optBoolean("isMine", false));
        c.canPin = value.optBoolean("canPin", value.optBoolean("isOwner", false));
        return c;
    }

    private void sendComment() {
        if (sending) return;
        final String text = input.getText() != null ? input.getText().toString().trim() : "";
        if (TextUtils.isEmpty(text)) return;
        sending = true;
        send.setAlpha(0.5f);
        postComment(text, null, null);
    }

    private void postComment(String text, String gifId, String gifUrl) {
        final JSONObject body = new JSONObject();
        try {
            if (!TextUtils.isEmpty(text)) body.put("text", text);
            if (!TextUtils.isEmpty(gifId)) body.put("gifId", gifId);
            if (!TextUtils.isEmpty(gifUrl)) body.put("gifUrl", gifUrl);
        } catch (JSONException ignored) {
            sending = false;
            send.setAlpha(1f);
            return;
        }
        final Comment replyTo = replyingTo;
        final String path = replyTo != null
                ? commentsPath() + "/" + Uri.encode(replyTo.id) + "/replies"
                : commentsPath();
        ReelsBackendApi.request(currentAccount, "POST", path, body, null, true, (response, status) -> {
            if (closed) return;
            sending = false;
            send.setAlpha(1f);
            if (status < 200 || status >= 300 || response == null) {
                Toast.makeText(getContext(), LocaleController.getString(R.string.ShortsCommentFailed), Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                final JSONObject result = new JSONObject(response);
                final Comment c = parseComment(result, replyTo != null ? 1 : 0, replyTo != null ? replyTo.id : null);
                if (c != null) {
                    if (replyTo != null) {
                        int parentIndex = comments.indexOf(replyTo);
                        int insertAt = parentIndex >= 0 ? parentIndex + 1 : comments.size();
                        while (insertAt < comments.size() && comments.get(insertAt).depth > 0) {
                            insertAt++;
                        }
                        comments.add(insertAt, c);
                        replyTo.replyCount++;
                        replyTo.repliesLoaded = true;
                    } else {
                        comments.add(c);
                    }
                    final int serverTotal = result.optInt("total", result.optInt("commentCount", result.optInt("comments", -1)));
                    if (serverTotal >= 0) {
                        totalCount = serverTotal;
                        item.comments = serverTotal;
                    } else {
                        totalCount++;
                        item.comments = totalCount;
                    }
                    input.setText("");
                    clearReply();
                    adapter.notifyDataSetChanged();
                    updateTitle();
                    updateState();
                    list.scrollToPosition(comments.indexOf(c));
                    notifyChanged();
                    refreshCount();
                }
            } catch (JSONException ignored) {
            }
        });
    }

    private void showGifDialog() {
        final EditText input = new EditText(getContext());
        input.setHint(LocaleController.getString(R.string.ShortsGifUrlHint));
        input.setSingleLine(true);
        new AlertDialog.Builder(getContext())
                .setTitle(LocaleController.getString(R.string.ShortsGif))
                .setView(input)
                .setPositiveButton(LocaleController.getString(R.string.ShortsAttachGif), (dialog, which) -> {
                    final String url = input.getText() != null ? input.getText().toString().trim() : "";
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        downloadGif(url);
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void downloadGif(String url) {
        if (downloadingGif) return;
        downloadingGif = true;
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            byte[] bytes = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("Accept", "image/gif");
                if (connection.getResponseCode() == 200) {
                    try (InputStream stream = connection.getInputStream();
                         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        final byte[] buffer = new byte[16 * 1024];
                        int read;
                        while ((read = stream.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            if (out.size() > MAX_GIF_BYTES) break;
                        }
                        if (out.size() > 0 && out.size() <= MAX_GIF_BYTES) bytes = out.toByteArray();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
            final byte[] gif = bytes;
            AndroidUtilities.runOnUIThread(() -> {
                downloadingGif = false;
                if (gif == null) {
                    Toast.makeText(getContext(), LocaleController.getString(R.string.ShortsGifUploadFailed), Toast.LENGTH_SHORT).show();
                    return;
                }
                ReelsBackendApi.uploadCommentGif(currentAccount, gif, (body, status) -> {
                    if (closed || status < 200 || status >= 300 || body == null) {
                        Toast.makeText(getContext(), LocaleController.getString(R.string.ShortsGifUploadFailed), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        final JSONObject result = new JSONObject(body);
                        String gifId = result.optString("gifId", result.optString("id", ""));
                        String gifUrl = result.optString("gifUrl", result.optString("url", result.optString("mediaUrl", "")));
                        if (TextUtils.isEmpty(gifId) && TextUtils.isEmpty(gifUrl)) {
                            Toast.makeText(getContext(), LocaleController.getString(R.string.ShortsGifUploadFailed), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        sending = true;
                        send.setAlpha(0.5f);
                        postComment("", gifId, gifUrl);
                    } catch (JSONException ignored) {
                        Toast.makeText(getContext(), LocaleController.getString(R.string.ShortsGifUploadFailed), Toast.LENGTH_SHORT).show();
                    }
                });
            });
        });
    }

    @Nullable
    private Comment commentAt(int position) {
        return position >= 0 && position < comments.size() ? comments.get(position) : null;
    }

    private void clearReply() {
        replyingTo = null;
        replyChip.setVisibility(View.GONE);
        input.setHint(LocaleController.getString(R.string.LeaveAComment));
    }

    private void startReply(Comment c) {
        if (c == null) return;
        replyingTo = c;
        replyChip.setText(LocaleController.formatString(R.string.ShortsReplyTo, c.author));
        replyChip.setVisibility(View.VISIBLE);
        input.setHint(LocaleController.formatString(R.string.ShortsReplyTo, c.author));
        input.requestFocus();
    }

    private void loadReplies(Comment parent) {
        if (parent == null || parent.repliesLoaded || !loadingReplies.add(parent.id)) return;
        final String path = commentsPath() + "/" + Uri.encode(parent.id) + "/replies?limit=50";
        ReelsBackendApi.request(currentAccount, "GET", path, null, null, true, (body, status) -> {
            if (closed) return;
            loadingReplies.remove(parent.id);
            parent.repliesLoaded = status >= 200 && status < 300 && body != null;
            if (parent.repliesLoaded) {
                try {
                    final JSONObject object = new JSONObject(body);
                    final JSONArray values = object.optJSONArray("items");
                    int insertAt = comments.indexOf(parent) + 1;
                    while (insertAt < comments.size() && comments.get(insertAt).depth > 0) insertAt++;
                    if (values != null) {
                        for (int i = 0; i < values.length(); i++) {
                            final Comment c = parseComment(values.optJSONObject(i), 1, parent.id);
                            if (c != null && findById(c.id) == null) {
                                comments.add(insertAt++, c);
                            }
                        }
                        parent.replyCount = Math.max(parent.replyCount, values.length());
                    }
                    adapter.notifyDataSetChanged();
                } catch (JSONException ignored) {
                }
            }
        });
    }

    private void toggleLike(Comment c) {
        if (c == null) return;
        final boolean target = !c.liked;
        final int previousLikes = c.likes;
        c.liked = target;
        c.likes = Math.max(0, c.likes + (target ? 1 : -1));
        adapter.notifyItemChanged(comments.indexOf(c));
        final JSONObject body = new JSONObject();
        try {
            body.put("liked", target);
        } catch (JSONException ignored) {
            return;
        }
        ReelsBackendApi.request(currentAccount, "PUT", commentsPath() + "/" + Uri.encode(c.id) + "/like",
                body, null, true, (response, status) -> {
                    if (closed) return;
                    if (status >= 200 && status < 300 && response != null) {
                        try {
                            final JSONObject result = new JSONObject(response);
                            c.liked = result.optBoolean("liked", target);
                            c.likes = result.optInt("likes", result.optInt("likeCount", c.likes));
                        } catch (JSONException ignored) {
                        }
                    } else {
                        c.liked = !target;
                        c.likes = previousLikes;
                    }
                    adapter.notifyItemChanged(comments.indexOf(c));
                });
    }

    private void showMenu(Comment c, View anchor) {
        if (c == null) return;
        final PopupMenu menu = new PopupMenu(getContext(), anchor);
        if (c.canPin) {
            menu.getMenu().add(0, 1, 0, LocaleController.getString(c.pinned ? R.string.ShortsUnpin : R.string.ShortsPin));
        }
        if (c.canDelete) {
            menu.getMenu().add(0, 2, 1, LocaleController.getString(R.string.ShortsDeleteComment));
        }
        if (menu.getMenu().size() == 0) return;
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                setPinned(c, !c.pinned);
                return true;
            }
            if (item.getItemId() == 2) {
                confirmDelete(c);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void setPinned(Comment c, boolean pinned) {
        final String method = pinned ? "PUT" : "DELETE";
        ReelsBackendApi.request(currentAccount, method, commentsPath() + "/" + Uri.encode(c.id) + "/pin",
                null, null, true, (response, status) -> {
                    if (closed || status < 200 || status >= 300) return;
                    c.pinned = pinned;
                    if (pinned) {
                        for (Comment other : comments) {
                            if (other != c) other.pinned = false;
                        }
                    }
                    adapter.notifyDataSetChanged();
                });
    }

    private void confirmDelete(Comment c) {
        new AlertDialog.Builder(getContext())
                .setTitle(LocaleController.getString(R.string.Delete))
                .setMessage(LocaleController.getString(R.string.ShortsDeleteComment))
                .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> deleteComment(c))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void deleteComment(Comment c) {
        ReelsBackendApi.request(currentAccount, "DELETE", commentsPath() + "/" + Uri.encode(c.id),
                null, null, true, (response, status) -> {
                    if (closed || status < 200 || status >= 300) return;
                    final int index = comments.indexOf(c);
                    if (index >= 0) comments.remove(index);
                    if (c.depth > 0 && c.parentId != null) {
                        final Comment parent = findById(c.parentId);
                        if (parent != null) parent.replyCount = Math.max(0, parent.replyCount - 1);
                    } else {
                        totalCount = Math.max(0, totalCount - 1);
                        item.comments = totalCount;
                    }
                    adapter.notifyDataSetChanged();
                    updateTitle();
                    updateState();
                    notifyChanged();
                    refreshCount();
                });
    }

    private void refreshCount() {
        if (item.source != ReelsItem.Source.PROFILE) return;
        new ReelsRepository(currentAccount).refreshProfileItem(item, (updated, error) -> {
            totalCount = item.comments;
            updateTitle();
            notifyChanged();
        });
    }

    private void notifyChanged() {
        if (onChanged != null) onChanged.run();
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

    private final class Adapter extends RecyclerView.Adapter<CommentHolder> {
        @NonNull
        @Override
        public CommentHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CommentHolder(parent.getContext());
        }

        @Override
        public void onBindViewHolder(@NonNull CommentHolder holder, int position) {
            holder.bind(comments.get(position));
        }

        @Override
        public int getItemCount() {
            return comments.size();
        }
    }

    private final class CommentHolder extends RecyclerView.ViewHolder {
        final LinearLayout cell;
        final LinearLayout headerRow;
        final TextView author;
        final TextView pinBadge;
        final TextView menuButton;
        final TextView text;
        final BackupImageView gif;
        final TextView like;
        final TextView reply;
        final TextView replies;

        CommentHolder(Context context) {
            super(new LinearLayout(context));
            cell = (LinearLayout) itemView;
            cell.setOrientation(LinearLayout.VERTICAL);

            headerRow = new LinearLayout(context);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);

            author = new TextView(context);
            author.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            author.setTypeface(AndroidUtilities.bold());
            author.setSingleLine(true);
            headerRow.addView(author, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            pinBadge = new TextView(context);
            pinBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
            pinBadge.setTypeface(AndroidUtilities.bold());
            pinBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
            pinBadge.setText(LocaleController.getString(R.string.ShortsPinned));
            headerRow.addView(pinBadge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            menuButton = new TextView(context);
            menuButton.setText("\u22EF");
            menuButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            menuButton.setGravity(Gravity.CENTER);
            menuButton.setPadding(dp(8), 0, dp(8), 0);
            headerRow.addView(menuButton, LayoutHelper.createLinear(32, 32));
            cell.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            text.setLineSpacing(dp(2), 1f);
            cell.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            gif = new BackupImageView(context);
            gif.setRoundRadius(dp(8));
            cell.addView(gif, LayoutHelper.createLinear(200, 140, 0, 6, 0, 0));

            final LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);

            like = new TextView(context);
            like.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            like.setCompoundDrawablePadding(dp(4));
            like.setPadding(0, dp(6), dp(16), dp(6));
            actions.addView(like, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            reply = new TextView(context);
            reply.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            reply.setText(LocaleController.getString(R.string.ShortsReply));
            reply.setCompoundDrawablePadding(dp(4));
            reply.setPadding(0, dp(6), dp(16), dp(6));
            actions.addView(reply, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            replies = new TextView(context);
            replies.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            replies.setPadding(0, dp(6), 0, dp(6));
            actions.addView(replies, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            cell.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            like.setOnClickListener(v -> toggleLike(commentAt(getAdapterPosition())));
            reply.setOnClickListener(v -> startReply(commentAt(getAdapterPosition())));
            replies.setOnClickListener(v -> loadReplies(commentAt(getAdapterPosition())));
            menuButton.setOnClickListener(v -> showMenu(commentAt(getAdapterPosition()), menuButton));
            cell.setOnLongClickListener(v -> {
                showMenu(commentAt(getAdapterPosition()), menuButton);
                return true;
            });
        }

        void bind(Comment c) {
            cell.setPadding(dp(20 + (c.depth > 0 ? 28 : 0)), dp(8), dp(20), dp(8));
            author.setText(!TextUtils.isEmpty(c.author) ? "@" + c.author : "");
            author.setTextColor(ReelsTheme.sheetText(provider));
            pinBadge.setTextColor(ReelsTheme.sheetAccent(provider));
            pinBadge.setVisibility(c.pinned ? View.VISIBLE : View.GONE);
            menuButton.setTextColor(ReelsTheme.textSecondary(provider));
            menuButton.setVisibility(c.canDelete || c.canPin ? View.VISIBLE : View.GONE);

            text.setText(c.text);
            text.setTextColor(ReelsTheme.sheetText(provider));
            text.setVisibility(TextUtils.isEmpty(c.text) ? View.GONE : View.VISIBLE);

            if (!TextUtils.isEmpty(c.gifUrl)) {
                gif.setVisibility(View.VISIBLE);
                gif.setImage(c.gifUrl, null, (Drawable) null);
            } else {
                gif.clearImage();
                gif.setVisibility(View.GONE);
            }

            like.setTextColor(c.liked ? ReelsTheme.sheetAccent(provider) : ReelsTheme.textSecondary(provider));
            like.setText(c.likes > 0 ? String.valueOf(c.likes) : LocaleController.getString(R.string.ShortsLike));
            like.setCompoundDrawablesWithIntrinsicBounds(tinted(R.drawable.msg_fave,
                    c.liked ? ReelsTheme.sheetAccent(provider) : ReelsTheme.textSecondary(provider)), null, null, null);

            reply.setTextColor(ReelsTheme.textSecondary(provider));
            reply.setText(c.replyCount > 0 && !c.repliesLoaded
                    ? LocaleController.formatString(R.string.ShortsReplies, c.replyCount)
                    : LocaleController.getString(R.string.ShortsReply));

            replies.setVisibility(c.depth == 0 && c.replyCount > 0 ? View.VISIBLE : View.GONE);
            replies.setText(LocaleController.formatString(R.string.ShortsReplies, c.replyCount));
            replies.setTextColor(ReelsTheme.sheetAccent(provider));
        }

        Drawable tinted(int res, int color) {
            final Drawable drawable = cell.getContext().getResources().getDrawable(res).mutate();
            drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            return drawable;
        }
    }
}
