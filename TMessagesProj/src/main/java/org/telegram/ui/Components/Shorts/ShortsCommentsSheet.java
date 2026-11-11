package org.telegram.ui.Components.Shorts;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.util.ArrayList;

/** Native comments sheet: messages.getDiscussionMessage, then messages.getReplies on the discussion thread. */
public class ShortsCommentsSheet extends BottomSheet {

    private final ShortsRepository repository;
    private final ShortsItem item;
    private final ArrayList<MessageObject> replies = new ArrayList<>();
    private ShortsRepository.Discussion discussion;
    private boolean loading;
    private boolean endReached;
    private int requestId;

    private final TextView titleView;
    private final RecyclerView listView;
    private final TextView emptyView;
    private final RadialProgressView progressView;
    private final LinearLayout inputRow;
    private final EditTextBoldCursor inputField;
    private final ImageView sendButton;
    private final RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;

    public ShortsCommentsSheet(Context context, ShortsRepository repository, ShortsItem item) {
        super(context, true);
        this.repository = repository;
        this.item = item;
        currentAccount = item.account;
        setApplyBottomPadding(false);
        setApplyTopPadding(false);
        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        titleView = new TextView(context);
        titleView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setText(LocaleController.getString(R.string.ShortsComments));
        root.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56, 20, 0, 20, 0));

        final FrameLayout listContainer = new FrameLayout(context);
        root.addView(listContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, (int) (AndroidUtilities.displaySize.y * 0.6f / AndroidUtilities.density)));

        listView = new RecyclerView(context);
        final LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        listView.setLayoutManager(layoutManager);
        listView.setAdapter(adapter = new Adapter());
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (layoutManager.findLastVisibleItemPosition() >= replies.size() - 5) {
                    loadMore();
                }
            }
        });
        listContainer.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        listContainer.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 24, 0, 24, 0));

        progressView = new RadialProgressView(context);
        listContainer.addView(progressView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setVisibility(View.GONE);
        root.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));

        inputField = new EditTextBoldCursor(context);
        inputField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        inputField.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        inputField.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        inputField.setHint(LocaleController.getString(R.string.LeaveAComment));
        inputField.setBackground(null);
        inputField.setMaxLines(4);
        inputField.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        inputField.setCursorColor(getThemedColor(Theme.key_dialogTextBlack));
        inputField.setPadding(dp(20), dp(12), dp(8), dp(12));
        inputRow.addView(inputField, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        sendButton = new ImageView(context);
        sendButton.setImageResource(R.drawable.send_plane_24);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setColorFilter(getThemedColor(Theme.key_chat_messagePanelSend));
        sendButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector)));
        sendButton.setContentDescription(LocaleController.getString(R.string.Send));
        sendButton.setOnClickListener(v -> send());
        inputRow.addView(sendButton, LayoutHelper.createLinear(48, 48, 0, 0, 8, 0));
        inputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });

        setCustomView(root);
        load();
    }

    private void load() {
        if (!item.commentsEnabled) {
            showEmpty(LocaleController.getString(R.string.ShortsCommentsDisabled));
            return;
        }
        loading = true;
        repository.loadDiscussion(item, (result, error) -> {
            loading = false;
            if (result == null) {
                showEmpty(LocaleController.getString(error != null ? R.string.ErrorOccurred : R.string.ShortsCommentsDisabled));
                return;
            }
            discussion = result;
            inputRow.setVisibility(result.canWrite ? View.VISIBLE : View.GONE);
            loadMore();
        });
    }

    private void loadMore() {
        if (discussion == null || loading || endReached) {
            return;
        }
        loading = true;
        final int offsetId = replies.isEmpty() ? 0 : replies.get(replies.size() - 1).getId();
        requestId = repository.loadReplies(discussion, offsetId, (page, end, error) -> {
            loading = false;
            requestId = 0;
            endReached = end || error != null;
            final int start = replies.size();
            replies.addAll(page);
            adapter.notifyItemRangeInserted(start, page.size());
            progressView.setVisibility(View.GONE);
            if (replies.isEmpty()) {
                showEmpty(LocaleController.getString(error != null ? R.string.ErrorOccurred : R.string.NoComments));
            } else {
                emptyView.setVisibility(View.GONE);
            }
        });
    }

    private void showEmpty(String text) {
        progressView.setVisibility(View.GONE);
        emptyView.setText(text);
        emptyView.setVisibility(View.VISIBLE);
    }

    private void send() {
        final String text = inputField.getText() != null ? inputField.getText().toString().trim() : "";
        if (discussion == null || TextUtils.isEmpty(text)) {
            return;
        }
        sendButton.setEnabled(false);
        repository.sendComment(discussion, text, error -> {
            sendButton.setEnabled(true);
            if (error != null) {
                AndroidUtilities.shakeViewSpring(inputField);
                return;
            }
            inputField.setText("");
            item.comments++;
            final int count = replies.size();
            replies.clear();
            adapter.notifyItemRangeRemoved(0, count);
            endReached = false;
            loadMore();
        });
    }

    @Override
    public void dismiss() {
        if (requestId != 0) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(requestId, true);
            requestId = 0;
        }
        AndroidUtilities.hideKeyboard(inputField);
        super.dismiss();
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final ReplyCell cell = new ReplyCell(parent.getContext());
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(cell) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((ReplyCell) holder.itemView).set(replies.get(position));
        }

        @Override
        public int getItemCount() {
            return replies.size();
        }
    }

    @SuppressLint("ViewConstructor")
    private class ReplyCell extends LinearLayout {
        private final BackupImageView avatar;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView name;
        private final TextView text;

        ReplyCell(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(16), dp(8), dp(16), dp(8));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(18));
            addView(avatar, LayoutHelper.createLinear(36, 36));
            final LinearLayout column = new LinearLayout(context);
            column.setOrientation(VERTICAL);
            addView(column, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            name.setTypeface(AndroidUtilities.bold());
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            column.addView(name);
            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            text.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            column.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        void set(MessageObject message) {
            final MessagesController controller = MessagesController.getInstance(currentAccount);
            final long fromId = message.getFromChatId();
            TLObject sender;
            String title;
            if (fromId > 0) {
                final TLRPC.User user = controller.getUser(fromId);
                sender = user;
                title = UserObject.getUserName(user);
                avatarDrawable.setInfo(currentAccount, user);
            } else {
                final TLRPC.Chat chat = controller.getChat(-fromId);
                sender = chat;
                title = chat != null ? chat.title : "";
                avatarDrawable.setInfo(currentAccount, chat);
            }
            avatar.setForUserOrChat(sender, avatarDrawable);
            name.setText(title + "  \u00B7  " + LocaleController.formatDateAudio(message.messageOwner.date, true));
            final CharSequence body = message.messageOwner.message;
            text.setText(TextUtils.isEmpty(body) ? message.messageText : body);
        }
    }
}
