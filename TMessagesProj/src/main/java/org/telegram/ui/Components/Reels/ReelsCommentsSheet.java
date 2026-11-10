package org.telegram.ui.Components.Reels;

import static org.telegram.messenger.AndroidUtilities.dp;

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
public class ReelsCommentsSheet extends BottomSheet {

    private final ReelsRepository repository;
    private final ReelsItem item;
    private final ArrayList<MessageObject> replies = new ArrayList<>();
    private ReelsRepository.Discussion discussion;
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

    public ReelsCommentsSheet(Context context, ReelsRepository repository, ReelsItem item) {
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
        emptyView.setText(LocaleController.getString(R.string.NoComments));
        emptyView.setVisibility(View.GONE);
        listContainer.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        progressView = new RadialProgressView(context);
        progressView.setSize(dp(36));
        progressView.setProgressColor(getThemedColor(Theme.key_progressCircle));
        progressView.setVisibility(View.GONE);
        listContainer.addView(progressView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(dp(12), dp(6), dp(12), dp(8));

        inputField = new EditTextBoldCursor(context);
        inputField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        inputField.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        inputField.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        inputField.setHint(LocaleController.getString(R.string.LeaveAComment));
        inputField.setBackground(null);
        inputField.setMaxLines(4);
        inputField.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        inputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendComment();
                return true;
            }
            return false;
        });
        inputRow.addView(inputField, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        sendButton = new ImageView(context);
        sendButton.setImageResource(R.drawable.ic_send);
        sendButton.setColorFilter(getThemedColor(Theme.key_dialogButton));
        sendButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        sendButton.setOnClickListener(v -> sendComment());
        inputRow.addView(sendButton, LayoutHelper.createLinear(40, 40));

        root.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        setCustomView(root);
        loadDiscussion();
    }

    private void loadDiscussion() {
        loading = true;
        updateState();
        repository.loadDiscussion(item, (discussionResult, error) -> {
            loading = false;
            if (error != null || discussionResult == null) {
                endReached = true;
                updateState();
                return;
            }
            this.discussion = discussionResult;
            inputRow.setVisibility(discussion.canWrite ? View.VISIBLE : View.GONE);
            loadMore();
        });
    }

    private void loadMore() {
        if (loading || endReached || discussion == null) {
            return;
        }
        loading = true;
        updateState();
        final int offsetId = replies.isEmpty() ? 0 : replies.get(replies.size() - 1).getId();
        requestId = repository.loadReplies(discussion, offsetId, (newReplies, isEnd, error) -> {
            loading = false;
            if (error != null) {
                endReached = true;
                updateState();
                return;
            }
            replies.addAll(newReplies);
            endReached = isEnd;
            adapter.notifyDataSetChanged();
            updateState();
        });
    }

    private void updateState() {
        progressView.setVisibility(loading && replies.isEmpty() ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(!loading && replies.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void sendComment() {
        final String text = inputField.getText() != null ? inputField.getText().toString().trim() : "";
        if (TextUtils.isEmpty(text) || discussion == null) {
            return;
        }
        inputField.setText("");
        repository.sendComment(discussion, text, error -> {
            if (error == null) {
                item.comments++;
            }
        });
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final LinearLayout cell = new LinearLayout(parent.getContext());
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setPadding(dp(16), dp(8), dp(16), dp(8));

            final BackupImageView avatar = new BackupImageView(parent.getContext());
            avatar.setRoundRadius(dp(18));
            cell.addView(avatar, LayoutHelper.createLinear(36, 36, Gravity.TOP, 0, 2, 12, 0));

            final LinearLayout textCol = new LinearLayout(parent.getContext());
            textCol.setOrientation(LinearLayout.VERTICAL);

            final TextView author = new TextView(parent.getContext());
            author.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            author.setTypeface(AndroidUtilities.bold());
            author.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            textCol.addView(author);

            final TextView message = new TextView(parent.getContext());
            message.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            message.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            textCol.addView(message, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            cell.addView(textCol, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(cell) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final LinearLayout cell = (LinearLayout) holder.itemView;
            final BackupImageView avatar = (BackupImageView) cell.getChildAt(0);
            final LinearLayout textCol = (LinearLayout) cell.getChildAt(1);
            final TextView author = (TextView) textCol.getChildAt(0);
            final TextView message = (TextView) textCol.getChildAt(1);

            final MessageObject msg = replies.get(position);
            message.setText(msg.messageText);

            final MessagesController controller = MessagesController.getInstance(item.account);
            final long fromId = msg.getFromChatId();
            if (fromId > 0) {
                final TLRPC.User user = controller.getUser(fromId);
                author.setText(UserObject.getUserName(user));
                final AvatarDrawable drawable = new AvatarDrawable();
                drawable.setInfo(user);
                avatar.setForUserOrChat(user, drawable);
            } else {
                final TLRPC.Chat chat = controller.getChat(-fromId);
                author.setText(chat != null ? chat.title : "");
                final AvatarDrawable drawable = new AvatarDrawable();
                drawable.setInfo(chat);
                avatar.setForUserOrChat(chat, drawable);
            }
        }

        @Override
        public int getItemCount() {
            return replies.size();
        }
    }
}
