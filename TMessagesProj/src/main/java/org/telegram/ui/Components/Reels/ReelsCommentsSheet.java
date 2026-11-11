package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/** The only Reels comments surface. It posts through POST /comments with parentId. */
public final class ReelsCommentsSheet extends BottomSheet {

    public interface Listener {
        void onCommentsChanged(ReelsItem item);
    }

    public interface GifPickerHost {
        void pickGif(ReelsCommentsSheet sheet);
    }

    private final int account;
    private final ReelsItem item;
    private final ReelsRepository repository;
    private final Listener listener;
    private final Theme.ResourcesProvider provider;
    private final LinearLayout content;
    private final ScrollView scrollView;
    private final LinearLayout inputRow;
    private final EditText input;
    private final TextView sendView;
    private final TextView gifView;
    private final TextView statusView;
    private final TextView titleView;
    private final HashMap<Long, LinearLayout> replyContainers = new HashMap<>();
    private final HashSet<Long> loadedReplyThreads = new HashSet<>();
    private final HashMap<Long, Long> replyNextAfter = new HashMap<>();
    private Long nextBefore;
    private ReelsComment replyTarget;
    private GifPickerHost gifPickerHost;
    private boolean loading;
    private boolean sending;
    private String pendingGifId;
    private String pendingGifUrl;
    private int pendingGifWidth;
    private int pendingGifHeight;

    public ReelsCommentsSheet(Context context, int account, ReelsItem item,
                              Theme.ResourcesProvider provider, Listener listener) {
        super(context, false, provider);
        this.account = account;
        this.item = item;
        this.provider = provider;
        this.listener = listener;
        this.repository = new ReelsRepository(account);
        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        setBackgroundColor(ReelsTheme.dialogBackground(provider));

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ReelsTheme.dialogBackground(provider));

        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(10), dp(10), dp(8));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        titleView = new TextView(context);
        titleView.setTextColor(ReelsTheme.dialogText(provider));
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView close = new TextView(context);
        close.setText("×");
        close.setTextSize(25);
        close.setTextColor(ReelsTheme.dialogSecondary(provider));
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close comments");
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(ReelsTheme.dialogBackground(provider));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(2), dp(12), dp(12));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(12), dp(6), dp(12), dp(10));
        footer.setBackgroundColor(ReelsTheme.dialogBackground(provider));
        root.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(context);
        statusView.setTextColor(ReelsTheme.dialogSecondary(provider));
        statusView.setTextSize(11);
        statusView.setVisibility(View.GONE);
        footer.addView(statusView);

        final TextView commentsOffView = new TextView(context);
        commentsOffView.setText("Comments are turned off.");
        commentsOffView.setTextColor(ReelsTheme.dialogSecondary(provider));
        commentsOffView.setTextSize(12);
        commentsOffView.setGravity(Gravity.CENTER_VERTICAL);
        commentsOffView.setPadding(0, dp(8), 0, dp(8));
        commentsOffView.setVisibility(item.commentsEnabled ? View.GONE : View.VISIBLE);
        footer.addView(commentsOffView);

        inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setVisibility(item.commentsEnabled ? View.VISIBLE : View.GONE);
        footer.addView(inputRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        input = new EditText(context);
        input.setHint(LocaleController.getString(R.string.ReelsCommentHint));
        input.setHintTextColor(ReelsTheme.dialogSecondary(provider));
        input.setTextColor(ReelsTheme.dialogText(provider));
        input.setTextSize(14);
        input.setMaxLines(3);
        input.setBackground(ReelsTheme.rounded(0x14000000, dp(18)));
        input.setPadding(dp(14), 0, dp(14), 0);
        inputRow.addView(input, new LinearLayout.LayoutParams(0, dp(42), 1f));

        gifView = pill(context, "GIF", ReelsTheme.dialogSecondary(provider));
        gifView.setContentDescription("Attach a GIF");
        gifView.setOnClickListener(v -> {
            if (gifPickerHost != null) {
                gifPickerHost.pickGif(this);
            } else {
                showStatus("Choose a GIF from the device.");
            }
        });
        final LinearLayout.LayoutParams gifParams = new LinearLayout.LayoutParams(dp(48), dp(38));
        gifParams.setMarginStart(dp(7));
        inputRow.addView(gifView, gifParams);

        sendView = pill(context, "Send", ReelsTheme.accent(provider));
        sendView.setTextColor(ReelsTheme.accentIcon(provider));
        sendView.setTypeface(Typeface.DEFAULT_BOLD);
        sendView.setContentDescription("Send comment");
        sendView.setOnClickListener(v -> post());
        final LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(58), dp(38));
        sendParams.setMarginStart(dp(7));
        inputRow.addView(sendView, sendParams);

        setCustomView(root);
        addTextChangedListener();
        loadPage(null, true);
    }

    public void setGifPickerHost(GifPickerHost host) {
        gifPickerHost = host;
    }

    /** Called by the host fragment after the local image picker returns raw GIF bytes. */
    public void onGifPicked(byte[] bytes) {
        if (!item.commentsEnabled) {
            showStatus("Comments are turned off.");
            return;
        }
        if (bytes == null || bytes.length == 0) {
            showStatus("No GIF was selected.");
            return;
        }
        showStatus("Uploading GIF…");
        repository.uploadGif(bytes, (gif, error) -> {
            if (error != null || gif == null) {
                showStatus(error == null ? "The GIF could not be uploaded." : error.describe());
                return;
            }
            pendingGifId = gif.id;
            pendingGifUrl = gif.url;
            pendingGifWidth = gif.width;
            pendingGifHeight = gif.height;
            showStatus("GIF attached.");
        });
    }

    private void addTextChangedListener() {
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                updateComposerState();
            }
        });
    }

    private void updateComposerState() {
        final boolean hasGif = !TextUtils.isEmpty(pendingGifId) || !TextUtils.isEmpty(pendingGifUrl);
        sendView.setEnabled(item.commentsEnabled && !sending && (hasGif || input.getText().length() > 0));
        sendView.setAlpha(sendView.isEnabled() ? 1f : 0.45f);
    }

    private void loadPage(Long before, boolean clear) {
        if (loading) return;
        if (clear) {
            content.removeAllViews();
            nextBefore = null;
            loadedReplyThreads.clear();
            replyNextAfter.clear();
            replyContainers.clear();
        }
        loading = true;
        if (clear) content.addView(loadingView("Loading comments…"));
        repository.loadComments(item, before, (page, error) -> {
            loading = false;
            if (clear) content.removeAllViews();
            if (error != null) {
                if (clear) addError(error.describe(), () -> loadPage(null, true));
                else addLoadMoreError(error.describe());
                return;
            }
            nextBefore = page.nextBefore;
            if (page.pinned.isEmpty() && page.items.isEmpty()) {
                if (clear) content.addView(emptyView("No comments yet."));
                return;
            }
            if (!page.pinned.isEmpty()) {
                content.addView(sectionLabel("Pinned"));
                for (ReelsComment comment : page.pinned) {
                    addComment(comment, false);
                }
            }
            for (ReelsComment comment : page.items) {
                addComment(comment, false);
            }
            if (nextBefore != null) {
                final TextView more = pill(getContext(), "View more comments",
                        ReelsTheme.dialogSecondary(provider));
                more.setOnClickListener(v -> {
                    more.setEnabled(false);
                    loadPage(nextBefore, false);
                });
                content.addView(more, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
            }
            updateTitle();
            updateComposerState();
        });
    }

    private void addComment(ReelsComment comment, boolean reply) {
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(reply ? dp(30) : 0, dp(8), 0, dp(8));

        final LinearLayout identity = new LinearLayout(getContext());
        identity.setOrientation(LinearLayout.HORIZONTAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(identity);

        final TextView author = new TextView(getContext());
        author.setText(comment.displayNameOrUsername());
        author.setTextColor(ReelsTheme.dialogText(provider));
        author.setTextSize(13);
        author.setTypeface(Typeface.DEFAULT_BOLD);
        identity.addView(author);

        final TextView time = new TextView(getContext());
        time.setText(comment.createdAtMs > 0
                ? android.text.format.DateUtils.getRelativeTimeSpanString(comment.createdAtMs)
                : "");
        time.setTextColor(ReelsTheme.dialogSecondary(provider));
        time.setTextSize(10);
        final LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeParams.setMarginStart(dp(7));
        identity.addView(time, timeParams);

        if (comment.pinned) {
            final TextView pinned = new TextView(getContext());
            pinned.setText("Pinned");
            pinned.setTextColor(ReelsTheme.accent(provider));
            pinned.setTextSize(10);
            final LinearLayout.LayoutParams pinnedParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pinnedParams.setMarginStart(dp(7));
            identity.addView(pinned, pinnedParams);
        }

        if (!TextUtils.isEmpty(comment.text)) {
            final TextView text = new TextView(getContext());
            text.setText(comment.text);
            text.setTextColor(ReelsTheme.dialogText(provider));
            text.setTextSize(14);
            text.setPadding(0, dp(3), 0, dp(4));
            row.addView(text);
        }

        if (!TextUtils.isEmpty(comment.gifUrl)) {
            final ReelsCoverView gif = new ReelsCoverView(getContext());
            gif.setCover(comment.gifUrl, "");
            final LinearLayout.LayoutParams gifParams = new LinearLayout.LayoutParams(
                    Math.min(dp(220), dp(comment.gifWidth > 0 ? 220 : 180)), dp(130));
            gifParams.topMargin = dp(2);
            gifParams.bottomMargin = dp(2);
            row.addView(gif, gifParams);
        }

        final LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(actions);

        final TextView like = pill(getContext(), "Like " + compact(comment.likes),
                comment.liked ? ReelsTheme.accent(provider) : ReelsTheme.dialogSecondary(provider));
        like.setContentDescription(comment.liked ? "Unlike comment" : "Like comment");
        like.setOnClickListener(v -> repository.likeComment(item, comment, !comment.liked, (updated, error) -> {
            if (error == null && updated != null) {
                like.setText("Like " + compact(updated.likes));
                like.setTextColor(updated.liked ? ReelsTheme.accent(provider) : ReelsTheme.dialogSecondary(provider));
            } else if (error != null) {
                showStatus(error.describe());
            }
        }));
        actions.addView(like);

        if (!reply && comment.canReply) {
            final TextView replyButton = pill(getContext(), "Reply", ReelsTheme.dialogSecondary(provider));
            replyButton.setOnClickListener(v -> beginReply(comment));
            final LinearLayout.LayoutParams replyParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            replyParams.setMarginStart(dp(7));
            actions.addView(replyButton, replyParams);
        }

        if (comment.canDelete || comment.canPin) {
            final TextView more = pill(getContext(), "•••", ReelsTheme.dialogSecondary(provider));
            more.setContentDescription("Comment actions");
            more.setOnClickListener(v -> showCommentMenu(more, comment));
            final LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            moreParams.setMarginStart(dp(7));
            actions.addView(more, moreParams);
        }

        content.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!reply && comment.replyCount > 0) {
            final LinearLayout replies = new LinearLayout(getContext());
            replies.setOrientation(LinearLayout.VERTICAL);
            replies.setPadding(dp(30), 0, 0, 0);
            content.addView(replies);
            replyContainers.put(comment.id, replies);
            final TextView showReplies = pill(getContext(), "View " + comment.replyCount + " replies",
                    ReelsTheme.dialogSecondary(provider));
            showReplies.setPadding(dp(30), dp(3), dp(10), dp(3));
            showReplies.setOnClickListener(v -> {
                showReplies.setVisibility(View.GONE);
                loadReplies(comment, replies, 0L);
            });
            content.addView(showReplies, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        }
    }

    private void loadReplies(ReelsComment parent, LinearLayout container, long after) {
        repository.loadReplies(item, parent.id, after, (page, error) -> {
            if (error != null) {
                showStatus(error.describe());
                return;
            }
            loadedReplyThreads.add(parent.id);
            for (ReelsComment reply : page.items) {
                addReply(container, reply);
            }
            if (page.nextAfter != null) {
                final TextView more = pill(getContext(), "More replies", ReelsTheme.dialogSecondary(provider));
                more.setOnClickListener(v -> {
                    more.setVisibility(View.GONE);
                    loadReplies(parent, container, page.nextAfter);
                });
                container.addView(more);
            }
            replyNextAfter.put(parent.id, page.nextAfter == null ? 0L : page.nextAfter);
        });
    }

    private void addReply(LinearLayout container, ReelsComment reply) {
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(2), 0, dp(2));
        final TextView author = new TextView(getContext());
        author.setText(reply.displayNameOrUsername());
        author.setTextColor(ReelsTheme.dialogText(provider));
        author.setTextSize(12);
        author.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(author);
        final TextView text = new TextView(getContext());
        text.setText(reply.text);
        text.setTextColor(ReelsTheme.dialogText(provider));
        text.setTextSize(13);
        row.addView(text);
        container.addView(row);
    }

    private void beginReply(ReelsComment comment) {
        if (!item.commentsEnabled || !comment.canReply) {
            showStatus("Replies are turned off.");
            return;
        }
        replyTarget = comment;
        input.setHint("Reply to @" + (comment.username == null ? comment.displayNameOrUsername() : comment.username));
        input.requestFocus();
        final InputMethodManager imm = (InputMethodManager) getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    private void post() {
        if (!item.commentsEnabled || sending) return;
        final String text = input.getText().toString().trim();
        if (TextUtils.isEmpty(text) && TextUtils.isEmpty(pendingGifId) && TextUtils.isEmpty(pendingGifUrl)) {
            return;
        }
        sending = true;
        updateComposerState();
        final long parentId = replyTarget == null ? 0 : replyTarget.id;
        repository.postComment(item, text, parentId, pendingGifId, pendingGifUrl, (comment, error) -> {
            sending = false;
            if (error != null) {
                showStatus(error.describe());
                updateComposerState();
                return;
            }
            input.setText("");
            replyTarget = null;
            pendingGifId = null;
            pendingGifUrl = null;
            pendingGifWidth = 0;
            pendingGifHeight = 0;
            showStatus(null);
            repository.refreshItem(item, (updated, refreshError) -> {
                if (updated != null) listener.onCommentsChanged(updated);
                updateTitle();
                loadPage(null, true);
            });
        });
    }

    private void showCommentMenu(View anchor, ReelsComment comment) {
        final boolean canDelete = comment.canDelete;
        final boolean canPin = comment.canPin && comment.parentId == 0;
        if (!canDelete && !canPin) return;
        final PopupMenu popup = new PopupMenu(getContext(), anchor);
        if (canPin) {
            popup.getMenu().add(0, 1, 0, comment.pinned ? "Unpin comment" : "Pin comment");
        }
        if (canDelete) {
            popup.getMenu().add(0, 2, 1, "Delete comment");
        }
        popup.setOnMenuItemClickListener(menuItem -> {
            if (menuItem.getItemId() == 1) {
                repository.pinComment(item, comment, !comment.pinned, (updated, error) -> {
                    if (error != null) showStatus(error.describe());
                    else loadPage(null, true);
                });
                return true;
            }
            if (menuItem.getItemId() == 2) {
                repository.deleteComment(item, comment.id, error -> {
                    if (error != null) showStatus(error.describe());
                    else {
                        repository.refreshItem(item, (updated, refreshError) -> {
                            if (updated != null) listener.onCommentsChanged(updated);
                            updateTitle();
                            loadPage(null, true);
                        });
                    }
                });
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void updateTitle() {
        titleView.setText(LocaleController.getString(R.string.ReelsComments) + " (" + item.comments + ")");
    }

    private void showStatus(String status) {
        if (TextUtils.isEmpty(status)) {
            statusView.setVisibility(View.GONE);
        } else {
            statusView.setText(status);
            statusView.setVisibility(View.VISIBLE);
        }
    }

    private void addLoadMoreError(String message) {
        final TextView retry = pill(getContext(), message + " Retry", ReelsTheme.dialogSecondary(provider));
        retry.setOnClickListener(v -> loadPage(nextBefore, false));
        content.addView(retry);
    }

    private void addError(String message, Runnable retry) {
        final LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dp(30), 0, dp(30));
        final TextView text = new TextView(getContext());
        text.setText(message);
        text.setTextColor(ReelsTheme.dialogSecondary(provider));
        text.setTextSize(13);
        text.setGravity(Gravity.CENTER);
        box.addView(text);
        final TextView button = pill(getContext(), "Retry", ReelsTheme.accent(provider));
        button.setTextColor(ReelsTheme.accentIcon(provider));
        button.setOnClickListener(v -> retry.run());
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        params.topMargin = dp(10);
        box.addView(button, params);
        content.addView(box);
    }

    private View loadingView(String text) {
        final TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(ReelsTheme.dialogSecondary(provider));
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(28), 0, dp(28));
        return view;
    }

    private View emptyView(String text) {
        final TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(ReelsTheme.dialogSecondary(provider));
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(28), 0, dp(28));
        return view;
    }

    private TextView sectionLabel(String text) {
        final TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(ReelsTheme.dialogSecondary(provider));
        view.setTextSize(11);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(8), 0, dp(2));
        return view;
    }

    private static TextView pill(Context context, String text, int color) {
        final TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(11);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 10), 0, dp(context, 10), 0);
        view.setBackground(ReelsTheme.rounded(0x12000000, dp(context, 15)));
        return view;
    }

    private int dp(float value) {
        return dp(getContext(), value);
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String compact(long value) {
        if (value >= 1_000_000L) return String.format(Locale.US, "%.1fM", value / 1_000_000d);
        if (value >= 1_000L) return String.format(Locale.US, "%.1fK", value / 1_000d);
        return String.valueOf(value);
    }
}
