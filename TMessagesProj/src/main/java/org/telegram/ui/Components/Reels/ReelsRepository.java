package org.telegram.ui.Components.Reels;

import android.net.Uri;
import android.text.TextUtils;
import android.util.SparseArray;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Pattern;

/**
 * The public vertical feed and profile-Reel interactions use REST. Channel-backed items
 * resolve to normal MTProto messages and keep their existing reaction/discussion RPCs.
 */
public final class ReelsRepository {

    public static final int PAGE_SIZE = 20;

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,31}");

    public interface FeedCallback {
        void onPage(ArrayList<ReelsItem> items, boolean endReached, TLRPC.TL_error error);
    }

    public interface ReactionCallback {
        void onResult(ReelsItem item, TLRPC.TL_error error);
    }

    public interface LinkCallback {
        int NOT_FOUND = 1;
        int NO_ACCESS = 2;

        void onResult(ReelsItem item, int failure);
    }

    public static final class Discussion {
        public TLRPC.InputPeer peer;
        public long dialogId;
        public int rootId;
        public boolean canWrite;
    }

    public interface DiscussionCallback {
        void onResult(Discussion discussion, TLRPC.TL_error error);
    }

    public interface RepliesCallback {
        void onResult(ArrayList<MessageObject> replies, boolean endReached, TLRPC.TL_error error);
    }

    private final int account;

    private String nextCursor;
    private boolean loading;
    private boolean endReached;
    private int requestGeneration;

    /** Clip -> desired liked state while a sendReaction for it is in flight. */
    private final SparseArray<Boolean> pendingLikes = new SparseArray<>();
    private final SparseArray<Boolean> inFlightLikes = new SparseArray<>();

    public ReelsRepository(int account) {
        this.account = account;
    }

    private MessagesController controller() {
        return MessagesController.getInstance(account);
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean isEndReached() {
        return endReached;
    }

    public void resetFeed() {
        requestGeneration++;
        nextCursor = null;
        loading = false;
        endReached = false;
    }

    /** A linked item is already inserted as page zero; REST feed pagination remains opaque. */
    public void startAfter(ReelsItem item) {
        endReached = false;
    }

    public void loadNextPage(FeedCallback callback) {
        if (loading || endReached) {
            return;
        }
        loading = true;
        final int generation = requestGeneration;
        String path = "/api/reels/feed?limit=" + PAGE_SIZE;
        if (nextCursor != null) {
            try {
                path += "&cursor=" + URLEncoder.encode(nextCursor, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                loading = false;
                callback.onPage(new ArrayList<>(), endReached, restError(0, "Invalid feed cursor"));
                return;
            }
        }
        ReelsBackendApi.request(account, "GET", path, null, null, false, (body, status) -> {
            if (generation != requestGeneration) return;
            loading = false;
            if (status < 200 || status >= 300 || body == null) {
                callback.onPage(new ArrayList<>(), endReached, restError(status, "Feed request failed"));
                return;
            }
            try {
                final JSONObject response = new JSONObject(body);
                final JSONArray values = response.optJSONArray("items");
                if (values == null) {
                    callback.onPage(new ArrayList<>(), endReached, restError(status, "Feed response has no items"));
                    return;
                }
                nextCursor = response.isNull("nextCursor") ? null : response.optString("nextCursor", null);
                endReached = nextCursor == null;
                resolveFeedItems(values, generation, callback);
            } catch (JSONException e) {
                callback.onPage(new ArrayList<>(), endReached, restError(status, "Invalid feed response"));
            }
        });
    }

    private void resolveFeedItems(JSONArray values, int generation, FeedCallback callback) {
        final ArrayList<ReelsItem> resolved = new ArrayList<>(Collections.nCopies(values.length(), null));
        final int[] pending = {0};
        for (int i = 0; i < values.length(); i++) {
            final JSONObject value = values.optJSONObject(i);
            if (value == null) continue;
            final String source = value.optString("source", "");
            if ("profile".equals(source)) {
                resolved.set(i, ReelsItem.fromProfile(account, value));
                continue;
            }
            if (!"channel".equals(source)) continue;
            final JSONObject channel = value.optJSONObject("channel");
            final String username = value.optString("channelUsername",
                    channel != null ? channel.optString("username", "") : "");
            final int messageId = value.optInt("messageId", value.optInt("message_id", 0));
            if (!isValidUsername(username) || messageId <= 0) continue;
            pending[0]++;
            final int index = i;
            resolveLink(account, username, messageId, (item, failure) -> {
                if (item != null) {
                    item.publicShareUrl = value.optString("shareUrl", "");
                    resolved.set(index, item);
                }
                if (--pending[0] == 0) {
                    finishResolvedFeed(resolved, generation, callback);
                }
            });
        }
        if (pending[0] == 0) {
            finishResolvedFeed(resolved, generation, callback);
        }
    }

    private void finishResolvedFeed(ArrayList<ReelsItem> resolved, int generation, FeedCallback callback) {
        if (generation != requestGeneration) return;
        final ArrayList<ReelsItem> items = new ArrayList<>();
        for (ReelsItem item : resolved) {
            if (item != null) items.add(item);
        }
        callback.onPage(items, endReached, null);
    }

    private static TLRPC.TL_error restError(int status, String message) {
        final TLRPC.TL_error error = new TLRPC.TL_error();
        error.code = status;
        error.text = message;
        return error;
    }

    /**
     * Optimistic heart toggle. Requests per clip are serialized: taps while a request is in
     * flight only update the desired state, which is sent once the previous request returns.
     */
    public void setLiked(ReelsItem item, boolean liked, ReactionCallback callback) {
        if (item.source == ReelsItem.Source.PROFILE) {
            setProfileLiked(item, liked, callback);
            return;
        }
        if (item.liked != liked) {
            item.liked = liked;
            item.likes = Math.max(0, item.likes + (liked ? 1 : -1));
        }

        if (inFlightLikes.get(item.messageId) != null) {
            pendingLikes.put(item.messageId, liked);
            return;
        }
        sendReaction(item, liked, callback);
    }

    private void setProfileLiked(ReelsItem item, boolean liked, ReactionCallback callback) {
        final boolean previousLiked = item.liked;
        final int previousCount = item.likes;
        item.liked = liked;
        item.likes = Math.max(0, previousCount + (liked == previousLiked ? 0 : liked ? 1 : -1));
        final JSONObject body = new JSONObject();
        try {
            body.put("liked", liked);
        } catch (JSONException e) {
            item.liked = previousLiked;
            item.likes = previousCount;
            callback.onResult(item, restError(0, "Could not create like request"));
            return;
        }
        final String path = "/api/reels/" + Uri.encode(item.reelId) + "/like";
        ReelsBackendApi.request(account, "PUT", path, body, null, true, (response, status) -> {
            if (status >= 200 && status < 300 && response != null) {
                try {
                    final JSONObject result = new JSONObject(response);
                    item.liked = result.optBoolean("liked", liked);
                    item.likes = result.optInt("likes", result.optInt("likeCount", result.optInt("count", item.likes)));
                    callback.onResult(item, null);
                    return;
                } catch (JSONException ignored) {
                }
            }
            item.liked = previousLiked;
            item.likes = previousCount;
            callback.onResult(item, restError(status, "Could not update Reel like"));
        });
    }

    private void sendReaction(ReelsItem item, boolean liked, ReactionCallback callback) {
        inFlightLikes.put(item.messageId, liked);
        final TLRPC.TL_messages_sendReaction req = new TLRPC.TL_messages_sendReaction();
        req.peer = controller().getInputPeer(item.getDialogId());
        req.msg_id = item.messageId;
        req.flags |= 1;
        if (liked) {
            final TLRPC.TL_reactionEmoji heart = new TLRPC.TL_reactionEmoji();
            heart.emoticon = ReelsItem.HEART;
            req.reaction.add(heart);
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.Updates) {
                controller().processUpdates((TLRPC.Updates) response, false);
            }
            AndroidUtilities.runOnUIThread(() -> {
                inFlightLikes.remove(item.messageId);
                final Boolean pending = pendingLikes.get(item.messageId);
                pendingLikes.remove(item.messageId);
                if (error != null) {
                    if (pending != null && pending != liked) {
                        sendReaction(item, pending, callback);
                        return;
                    }
                    item.liked = !liked;
                    item.likes = Math.max(0, item.likes + (liked ? -1 : 1));
                    callback.onResult(item, error);
                    return;
                }
                if (pending != null && pending != liked) {
                    sendReaction(item, pending, callback);
                    return;
                }
                callback.onResult(item, null);
            });
        });
    }

    public boolean hasPendingReaction(ReelsItem item) {
        return item.source == ReelsItem.Source.CHANNEL && inFlightLikes.get(item.messageId) != null;
    }

    public void loadDiscussion(ReelsItem item, DiscussionCallback callback) {
        final TLRPC.TL_messages_getDiscussionMessage req = new TLRPC.TL_messages_getDiscussionMessage();
        req.peer = controller().getInputPeer(item.getDialogId());
        req.msg_id = item.messageId;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.TL_messages_discussionMessage)) {
                callback.onResult(null, error);
                return;
            }
            final TLRPC.TL_messages_discussionMessage res = (TLRPC.TL_messages_discussionMessage) response;
            controller().putUsers(res.users, false);
            controller().putChats(res.chats, false);
            TLRPC.Message root = null;
            for (int i = 0; i < res.messages.size(); i++) {
                final TLRPC.Message m = res.messages.get(i);
                if (root == null || m.id < root.id) {
                    root = m;
                }
            }
            if (root == null) {
                callback.onResult(null, error);
                return;
            }
            final Discussion discussion = new Discussion();
            discussion.dialogId = MessageObject.getPeerId(root.peer_id);
            discussion.peer = controller().getInputPeer(discussion.dialogId);
            discussion.rootId = root.id;
            final TLRPC.Chat chat = controller().getChat(-discussion.dialogId);
            discussion.canWrite = chat != null && ChatObject.canSendMessages(chat);
            callback.onResult(discussion, null);
        }));
    }

    public int loadReplies(Discussion discussion, int offsetId, RepliesCallback callback) {
        final TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
        req.peer = discussion.peer;
        req.msg_id = discussion.rootId;
        req.offset_id = offsetId;
        req.limit = 50;
        return ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.messages_Messages)) {
                callback.onResult(new ArrayList<>(), true, error);
                return;
            }
            final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            controller().putUsers(res.users, false);
            controller().putChats(res.chats, false);
            final ArrayList<MessageObject> replies = new ArrayList<>();
            for (int i = 0; i < res.messages.size(); i++) {
                final TLRPC.Message m = res.messages.get(i);
                if (m instanceof TLRPC.TL_message && m.id != discussion.rootId) {
                    replies.add(new MessageObject(account, m, false, false));
                }
            }
            callback.onResult(replies, res.messages.size() < req.limit, null);
        }));
    }

    public void sendComment(Discussion discussion, String text, Utilities.Callback<TLRPC.TL_error> callback) {
        final TLRPC.TL_messages_sendMessage req = new TLRPC.TL_messages_sendMessage();
        req.peer = discussion.peer;
        req.message = text;
        req.random_id = Utilities.random.nextLong();
        final TLRPC.TL_inputReplyToMessage replyTo = new TLRPC.TL_inputReplyToMessage();
        replyTo.reply_to_msg_id = discussion.rootId;
        req.reply_to = replyTo;
        req.flags |= 1;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.Updates) {
                controller().processUpdates((TLRPC.Updates) response, false);
            }
            AndroidUtilities.runOnUIThread(() -> callback.run(error));
        });
    }

    public void exportLink(ReelsItem item, Utilities.Callback<String> callback) {
        if (item.source != ReelsItem.Source.CHANNEL) {
            callback.run(null);
            return;
        }
        final TLRPC.TL_channels_exportMessageLink req = new TLRPC.TL_channels_exportMessageLink();
        req.channel = controller().getInputChannel(item.channelId);
        req.id = item.messageId;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() ->
                callback.run(response instanceof TLRPC.TL_exportedMessageLink ? ((TLRPC.TL_exportedMessageLink) response).link : null)));
    }

    public void cancel() {
        requestGeneration++;
        loading = false;
    }

    public static boolean isValidUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    /**
     * /{username}/v/{messageId}: resolve the public channel, then load exactly that message
     * and require a playable video from that channel.
     */
    public static void resolveLink(int account, String username, int messageId, LinkCallback callback) {
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                callback.onResult(null, LinkCallback.NOT_FOUND);
                return;
            }
            final TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
            controller.putUsers(res.users, false);
            controller.putChats(res.chats, false);
            if (!(res.peer instanceof TLRPC.TL_peerChannel)) {
                callback.onResult(null, LinkCallback.NOT_FOUND);
                return;
            }
            final TLRPC.Chat chat = controller.getChat(res.peer.channel_id);
            if (chat == null || !ChatObject.isChannel(chat)) {
                callback.onResult(null, LinkCallback.NOT_FOUND);
                return;
            }
            loadClip(account, chat.id, messageId, callback);
        }));
    }

    public static void loadClip(int account, long channelId, int messageId, LinkCallback callback) {
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
        req.channel = controller.getInputChannel(channelId);
        req.id.add(messageId);
        ConnectionsManager.getInstance(account).sendRequest(req, (TLObject response, TLRPC.TL_error error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.messages_Messages)) {
                callback.onResult(null, isAccessError(error) ? LinkCallback.NO_ACCESS : LinkCallback.NOT_FOUND);
                return;
            }
            final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            controller.putUsers(res.users, false);
            controller.putChats(res.chats, false);
            for (int i = 0; i < res.messages.size(); i++) {
                final TLRPC.Message m = res.messages.get(i);
                if (m.id == messageId && m.peer_id != null && m.peer_id.channel_id == channelId) {
                    final ReelsItem item = ReelsItem.from(account, m);
                    callback.onResult(item, item != null ? 0 : LinkCallback.NOT_FOUND);
                    return;
                }
            }
            callback.onResult(null, LinkCallback.NOT_FOUND);
        }));
    }

    private static boolean isAccessError(TLRPC.TL_error error) {
        if (error == null || error.text == null) {
            return false;
        }
        return error.text.startsWith("CHANNEL_PRIVATE") || error.text.startsWith("CHANNEL_PUBLIC_GROUP_NA")
                || error.text.startsWith("CHAT_FORBIDDEN") || error.text.startsWith("USER_BANNED_IN_CHANNEL");
    }
}
