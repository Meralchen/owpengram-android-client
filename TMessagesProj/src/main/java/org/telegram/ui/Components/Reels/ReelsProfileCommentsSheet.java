package org.telegram.ui.Components.Reels;

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

/** REST comments for profile-owned Reels. */
public final class ReelsProfileCommentsSheet extends BottomSheet {

    private static final class Comment {
        String id;
        String author;
        String text;
    }

    private final ReelsItem item;
    private final ArrayList<Comment> comments = new ArrayList<>();
    private final RecyclerView list;
    private final Adapter adapter = new Adapter();
    private final TextView state;
    private final EditTextBoldCursor input;
    private final TextView send;
    private boolean loading;
    private boolean endReached;
    private boolean closed;

    public ReelsProfileCommentsSheet(Context context, ReelsItem item) {
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
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendComment();
                return true;
            }
            return false;
        });
        inputRow.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        send = new TextView(context);
        send.setText(LocaleController.getString(R.string.Send));
        send.setTextColor(getThemedColor(Theme.key_dialogButton));
        send.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        send.setTypeface(AndroidUtilities.bold());
        send.setGravity(Gravity.CENTER);
        send.setPadding(dp(16), dp(12), dp(20), dp(12));
        send.setOnClickListener(v -> sendComment());
        inputRow.addView(send, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        root.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        setCustomView(root);
        loadMore();
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

    private void loadMore() {
        if (loading || endReached || item == null) return;
        loading = true;
        updateState();
        String path = "/api/reels/" + android.net.Uri.encode(item.reelId) + "/comments?limit=30";
        if (!comments.isEmpty()) {
            try {
                path += "&before=" + URLEncoder.encode(comments.get(comments.size() - 1).id, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {
            }
        }
        ReelsBackendApi.request(currentAccount, "GET", path, null, null, true, (body, status) -> {
            if (closed) return;
            loading = false;
            if (status >= 200 && status < 300 && body != null) {
                try {
                    JSONObject object = new JSONObject(body);
                    JSONArray values = object.optJSONArray("items");
                    if (values != null) {
                        for (int i = 0; i < values.length(); i++) {
                            JSONObject v = values.optJSONObject(i);
                            if (v == null) continue;
                            Comment c = new Comment();
                            c.id = v.optString("id");
                            c.author = v.optString("username", v.optString("author", ""));
                            c.text = v.optString("text", "");
                            if (!TextUtils.isEmpty(c.id) && !TextUtils.isEmpty(c.text)) comments.add(c);
                        }
                        endReached = values.length() < 30;
                        adapter.notifyDataSetChanged();
                    }
                } catch (JSONException ignored) {
                }
            } else {
                endReached = true;
            }
            updateState();
        });
    }

    private void sendComment() {
        final String text = input.getText() != null ? input.getText().toString().trim() : "";
        if (TextUtils.isEmpty(text) || item == null) return;
        input.setText("");
        JSONObject body = new JSONObject();
        try {
            body.put("text", text);
        } catch (JSONException ignored) {
            return;
        }
        ReelsBackendApi.request(currentAccount, "POST", "/api/reels/" + android.net.Uri.encode(item.reelId) + "/comments", body, null, true, (response, status) -> {
            if (closed || status < 200 || status >= 300 || response == null) return;
            try {
                JSONObject v = new JSONObject(response);
                Comment c = new Comment();
                c.id = v.optString("id");
                c.author = v.optString("username", v.optString("author", ""));
                c.text = v.optString("text", text);
                comments.add(0, c);
                item.comments++;
                adapter.notifyItemInserted(0);
                list.scrollToPosition(0);
                updateState();
            } catch (JSONException ignored) {
            }
        });
    }

    @Override
    public void dismiss() {
        closed = true;
        super.dismiss();
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(parent.getContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dp(20), dp(8), dp(20), dp(8));
            TextView author = new TextView(parent.getContext());
            author.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            author.setTypeface(AndroidUtilities.bold());
            author.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            cell.addView(author);
            TextView text = new TextView(parent.getContext());
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            text.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            cell.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            return new RecyclerView.ViewHolder(cell) {};
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            LinearLayout cell = (LinearLayout) holder.itemView;
            Comment c = comments.get(position);
            ((TextView) cell.getChildAt(0)).setText(!TextUtils.isEmpty(c.author) ? "@" + c.author : "User");
            ((TextView) cell.getChildAt(1)).setText(c.text);
        }

        @Override public int getItemCount() {
            return comments.size();
        }
    }
}
