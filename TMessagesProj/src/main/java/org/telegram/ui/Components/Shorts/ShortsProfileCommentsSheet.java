package org.telegram.ui.Components.Shorts;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** REST comments for profile-owned Reels; channel discussions remain MTProto-backed. */
public final class ShortsProfileCommentsSheet extends BottomSheet {

    private static final class Comment {
        String id;
        String author;
        String text;
    }

    private final ShortsItem item;
    private final ArrayList<Comment> comments = new ArrayList<>();
    private final RecyclerView list;
    private final Adapter adapter = new Adapter();
    private final TextView state;
    private final EditTextBoldCursor input;
    private final TextView send;
    private boolean loading;
    private boolean endReached;
    private boolean closed;

    public ShortsProfileCommentsSheet(Context context, ShortsItem item) {
        super(context, true);
        this.item = item;
        currentAccount = item.account;
        setApplyBottomPadding(false);
        setApplyTopPadding(false);
        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.ShortsComments));
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56, 20, 0, 20, 0));

        list = new RecyclerView(context);
        LinearLayoutManager manager = new LinearLayoutManager(context);
        list.setLayoutManager(manager);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (manager.findLastVisibleItemPosition() >= comments.size() - 4) loadMore();
            }
        });
        root.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, (int) (AndroidUtilities.displaySize.y * 0.6f / AndroidUtilities.density)));

        state = new TextView(context);
        state.setGravity(Gravity.CENTER);
        state.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        state.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        root.addView(state, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));

        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditTextBoldCursor(context);
        input.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        input.setHint(LocaleController.getString(R.string.LeaveAComment));
        input.setBackground(null);
        input.setMaxLines(4);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setPadding(dp(20), dp(12), dp(8), dp(12));
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        inputRow.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        send = new TextView(context);
        send.setText(LocaleController.getString(R.string.Send));
        send.setTextColor(getThemedColor(Theme.key_chat_messagePanelSend));
        send.setGravity(Gravity.CENTER);
        send.setOnClickListener(v -> sendComment());
        inputRow.addView(send, LayoutHelper.createLinear(56, 48));
        root.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendComment();
                return true;
            }
            return false;
        });
        setCustomView(root);
        loadMore();
    }

    private void loadMore() {
        if (loading || endReached) return;
        loading = true;
        state.setText(LocaleController.getString(R.string.Loading));
        state.setVisibility(View.VISIBLE);
        String path = "/api/reels/" + android.net.Uri.encode(item.reelId) + "/comments?limit=30";
        if (!comments.isEmpty()) {
            try {
                path += "&before=" + URLEncoder.encode(comments.get(comments.size() - 1).id, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                loading = false;
                state.setText(LocaleController.getString(R.string.ErrorOccurred));
                return;
            }
        }
        ShortsBackendApi.request(currentAccount, "GET", path, null, null, true, (body, status) -> {
            if (closed) return;
            loading = false;
            if (status < 200 || status >= 300 || body == null) {
                state.setText(LocaleController.getString(R.string.ErrorOccurred));
                return;
            }
            try {
                JSONObject result = new JSONObject(body);
                JSONArray values = result.optJSONArray("items");
                if (values == null) values = result.optJSONArray("comments");
                if (values == null) throw new JSONException("Missing items");
                int start = comments.size();
                for (int i = 0; i < values.length(); i++) {
                    JSONObject value = values.optJSONObject(i);
                    if (value == null || TextUtils.isEmpty(value.optString("id", ""))) continue;
                    Comment comment = new Comment();
                    comment.id = value.optString("id");
                    comment.text = value.optString("text", "");
                    JSONObject author = value.optJSONObject("author");
                    if (author == null) author = value.optJSONObject("owner");
                    comment.author = author != null ? author.optString("displayName", author.optString("username", "")) : "";
                    comments.add(comment);
                }
                adapter.notifyItemRangeInserted(start, comments.size() - start);
                endReached = comments.size() == start || values.length() < 30;
                state.setVisibility(comments.isEmpty() ? View.VISIBLE : View.GONE);
                if (comments.isEmpty()) state.setText(LocaleController.getString(R.string.NoComments));
            } catch (JSONException e) {
                state.setText(LocaleController.getString(R.string.ErrorOccurred));
            }
        });
    }

    private void sendComment() {
        String text = input.getText() != null ? input.getText().toString().trim() : "";
        if (TextUtils.isEmpty(text)) return;
        JSONObject body = new JSONObject();
        try {
            body.put("text", text);
        } catch (JSONException e) {
            return;
        }
        send.setEnabled(false);
        ShortsBackendApi.request(currentAccount, "POST", "/api/reels/" + android.net.Uri.encode(item.reelId) + "/comments", body, null, true, (response, status) -> {
            if (closed) return;
            send.setEnabled(true);
            if (status < 200 || status >= 300) {
                AndroidUtilities.shakeViewSpring(input);
                return;
            }
            input.setText("");
            item.comments++;
            comments.clear();
            adapter.notifyDataSetChanged();
            endReached = false;
            loadMore();
        });
    }

    @Override
    public void dismiss() {
        closed = true;
        AndroidUtilities.hideKeyboard(input);
        super.dismiss();
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView view = new TextView(parent.getContext());
            view.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            view.setPadding(dp(20), dp(12), dp(20), dp(12));
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Comment comment = comments.get(position);
            ((TextView) holder.itemView).setText(TextUtils.isEmpty(comment.author) ? comment.text : comment.author + "\n" + comment.text);
        }

        @Override
        public int getItemCount() {
            return comments.size();
        }
    }
}
