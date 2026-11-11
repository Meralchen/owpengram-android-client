package org.telegram.ui.Components.Reels;

import android.net.Uri;
import android.text.TextUtils;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * Reels REST repository. Feed pages are delivered immediately with unresolved channel
 * placeholders; the viewer resolves a small window around the current page. Likes are keyed by
 * the stable server item id, never by a channel-local message id.
 */
public final class ReelsRepository {

    public static final int PAGE_SIZE = 20;

    public static final String MODE_FOR_YOU = "for_you";
    public static final String MODE_LATEST = "latest";
    public static final String MODE_FOLLOWING = "following";

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

    public static final class CreatorInfo {
        public long userId;
        public String username;
        public String displayName;
        public int reels;
        public int followers;
        public int following;
        public boolean isFollowing;
        public boolean isSelf;
    }

    public interface CreatorCallback {
        void onResult(CreatorInfo info, TLRPC.TL_error error);
    }

    public interface FollowCallback {
        void onResult(boolean following, int followers, int followingCount, TLRPC.TL_error error);
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
    private int nextOffset;
    private String mode = MODE_FOR_YOU;
    private String profileUsername;
    private boolean loading;
    private boolean endReached;
    private int requestGeneration;

    /** Stable item id -> desired liked state while a sendReaction for it is in flight. */
    private final HashMap<String, Boolean> pendingLikes = new HashMap<>();
    private final HashMap<String, Boolean> inFlightLikes = new HashMap<>();
    private final HashSet<String> resolvingChannels = new HashSet<>();

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

    public boolean isProfileFeed() {
        return !TextUtils.isEmpty(profileUsername);
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = TextUtils.isEmpty(mode) ? MODE_FOR_YOU : mode;
        this.profileUsername = null;
        resetFeed();
    }

    public void setProfile(String username) {
        this.profileUsername = username;
        resetFeed();
    }

    public void resetFeed() {
        requestGeneration++;
        nextCursor = null;
        nextOffset = 0;
        loading = false;
        endReached = false;
    }

    /** A linked item is already inserted as page zero; REST feed pagination remains opaque. */
    public void startAfter(ReelsItem item) {
        endReached = false;
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    private String feedPath() {
        if (isProfileFeed()) {
            return "/api/profiles/" + encode(profileUsername) + "/reels?limit=" + PAGE_SIZE + "&offset=" + nextOffset;
        }
        String path = "/api/reels/feed?mode=" + encode(mode) + "&limit=" + PAGE_SIZE;
        if (nextCursor != null) {
            path += "&cursor=" + encode(nextCursor);
        }
        return path;
    }

    public void loadNextPage(FeedCallback callback) {
        if (loading || endReached) {
            return;
        }
        loading = true;
        final int generation = requestGeneration;
        final int requestOffset = nextOffset;
        final String path = feedPath();
        ReelsBackendApi.request(account, "GET", path, null, null, true, (body, status) -> {
            if (generation != requestGeneration) return;
            if ((status == 401 || status == 0) && !MODE_FOLLOWING.equals(mode)) {
                // The public feed must still load when the export proof is unavailable.
                ReelsBackendApi.request(account, "GET", path, null, null, false, (publicBody, publicStatus) -> {
                    if (generation != requestGeneration) return;
                    finishPage(publicBody, publicStatus, generation, requestOffset, callback);
                });
            } else {
                finishPage(body, status, generation, requestOffset, callback);
            }
        });
    }

    private void finishPage(String body, int status, int generation, int requestOffset, FeedCallback callback) {
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
            final ArrayList<ReelsItem> page = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) {
                final JSONObject value = values.optJSONObject(i);
                if (value == null) continue;
                final String source = value.optString("source", "");
                final ReelsItem item;
                if ("channel".equals(source)) {
                    item = ReelsItem.fromChannelFeed(account, value);
                } else {
                    item = ReelsItem.fromProfile(account, value);
                }
                if (item != null) page.add(item);
            }
            if (isProfileFeed()) {
                if (!response.has("nextOffset") || response.isNull("nextOffset")) {
                    endReached = true;
                } else {
                    final int next = response.optInt("nextOffset", requestOffset + page.size());
                    endReached = next <= requestOffset;
                    nextOffset = endReached ? requestOffset : next;
                }
            } else {
                nextCursor = response.isNull("nextCursor") ? null : response.optString("nextCursor", null);
                endReached = nextCursor == null;
            }
            callback.onPage(page, endReached, null);
        } catch (JSONException e) {
            callback.onPage(new ArrayList<>(), endReached, restError(status, "Invalid feed response"));
        }
    }

    /**
     * Resolves the channel placeholders immediately around the current page. The callback runs on
     * the UI thread for every item whose MTProto message finished loading.
     */
    public void resolveChannelAround(ArrayList<ReelsItem> items, int center, Utilities.Callback<ReelsItem> callback) {
        if (items == null || items.isEmpty()) return;
        final int start = Math.max(0, center - 1);
        final int end = Math.min(items.size() - 1, center + 1);
        for (int i = start; i <= end; i++) {
            final ReelsItem item = items.get(i);
            if (item.source == ReelsItem.Source.CHANNEL && !item.resolved) {
                resolveChannelItem(item, callback);
            }
        }
    }

    private void resolveChannelItem(ReelsItem item, Utilities.Callback<ReelsItem> callback) {
        final String key = item.stableId();
        if (!resolvingChannels.add(key)) {
            return;
        }
        final LinkCallback resolvedCallback = (resolved, failure) -> {
            resolvingChannels.remove(key);
            if (resolved != null) {
                item.applyResolved(resolved);
            }
            if (callback != null) {
                callback.run(resolved != null ? item : null);
            }
        };
        if (item.messageId <= 0) {
            resolvingChannels.remove(key);
            if (callback != null) callback.run(null);
            return;
        }
        if (isValidUsername(item.channelUsername)) {
            resolveLink(account, item.channelUsername, item.messageId, resolvedCallback);
        } else if (item.channelId > 0) {
            loadClip(account, item.channelId, item.messageId, resolvedCallback);
        } else {
            resolvingChannels.remove(key);
            if (callback != null) callback.run(null);
        }
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
        if (!item.resolved) {
            resolveChannelItem(item, resolved -> {
                if (resolved == null) {
                    callback.onResult(item, restError(0, "Channel video unavailable"));
                } else {
                    setLiked(item, liked, callback);
                }
            });
            return;
        }
        if (item.liked != liked) {
            item.liked = liked;
            item.likes = Math.max(0, item.likes + (liked ? 1 : -1));
        }

        final String key = item.stableId();
        if (inFlightLikes.get(key) != null) {
            pendingLikes.put(key, liked);
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
        if (item.channelId == 0 || item.messageId <= 0) {
            callback.onResult(item, restError(0, "Channel video unavailable"));
            return;
        }
        final String key = item.stableId();
        inFlightLikes.put(key, liked);
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
                inFlightLikes.remove(key);
                final Boolean pending = pendingLikes.get(key);
                pendingLikes.remove(key);
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
        return item.source == ReelsItem.Source.CHANNEL && inFlightLikes.get(item.stableId()) != null;
    }

    /** Save/bookmark a profile Reel. Channel items have no REST save. */
    public void setSaved(ReelsItem item, boolean saved, ReactionCallback callback) {
        if (item.source != ReelsItem.Source.PROFILE) {
            callback.onResult(item, restError(400, "Saves are profile-only"));
            return;
        }
        final boolean previousSaved = item.saved;
        final int previousSaves = item.saves;
        item.saved = saved;
        item.saves = Math.max(0, previousSaves + (saved == previousSaved ? 0 : saved ? 1 : -1));
        final JSONObject body = new JSONObject();
        try {
            body.put("saved", saved);
        } catch (JSONException ignored) {
            item.saved = previousSaved;
            item.saves = previousSaves;
            callback.onResult(item, restError(0, "Could not create save request"));
            return;
        }
        final String path = "/api/reels/" + Uri.encode(item.reelId) + "/save";
        ReelsBackendApi.request(account, saved ? "PUT" : "DELETE", path, saved ? body : null, null, true, (response, status) -> {
            if (status >= 200 && status < 300) {
                if (response != null) {
                    try {
                        final JSONObject result = new JSONObject(response);
                        item.saved = result.optBoolean("saved", saved);
                        item.saves = result.optInt("saves", item.saves);
                    } catch (JSONException ignored) {
                    }
                }
                callback.onResult(item, null);
                return;
            }
            item.saved = previousSaved;
            item.saves = previousSaves;
            callback.onResult(item, restError(status, "Could not update Reel save"));
        });
    }

    /** Debounced playback signal: start once and completion once per profile view. */
    public void reportView(ReelsItem item, long positionMs, boolean completed) {
        if (item == null || item.source != ReelsItem.Source.PROFILE) return;
        final JSONObject body = new JSONObject();
        try {
            body.put("positionMs", Math.max(0, positionMs));
            body.put("completed", completed);
        } catch (JSONException ignored) {
            return;
        }
        ReelsBackendApi.request(account, "POST", "/api/reels/" + Uri.encode(item.reelId) + "/view",
                body, null, true, (response, status) -> {
                    if (status >= 200 && status < 300 && response != null) {
                        try {
                            item.views = new JSONObject(response).optInt("views",
                                    new JSONObject(response).optInt("viewCount", item.views));
                        } catch (JSONException ignored) {
                        }
                    }
                });
    }

    public void reportShare(ReelsItem item, ReactionCallback callback) {
        if (item == null || item.source != ReelsItem.Source.PROFILE) {
            if (callback != null) callback.onResult(item, null);
            return;
        }
        final int previousShares = item.shares;
        item.shares++;
        ReelsBackendApi.request(account, "POST", "/api/reels/" + Uri.encode(item.reelId) + "/share",
                null, null, true, (response, status) -> {
                    if (status >= 200 && status < 300) {
                        if (response != null) {
                            try {
                                final JSONObject result = new JSONObject(response);
                                item.shares = result.optInt("shares", result.optInt("shareCount", item.shares));
                            } catch (JSONException ignored) {
                            }
                        }
                        if (callback != null) callback.onResult(item, null);
                    } else {
                        item.shares = previousShares;
                        if (callback != null) callback.onResult(item, restError(status, "Could not update share count"));
                    }
                });
    }

    /** Reconciles a profile item's server-computed counters after a comment is posted or deleted. */
    public void refreshProfileItem(ReelsItem item, ReactionCallback callback) {
        if (item == null || item.source != ReelsItem.Source.PROFILE) {
            if (callback != null) callback.onResult(item, null);
            return;
        }
        ReelsBackendApi.request(account, "GET", "/api/reels/" + Uri.encode(item.reelId),
                null, null, true, (body, status) -> {
                    if (status >= 200 && status < 300 && body != null) {
                        try {
                            final JSONObject result = new JSONObject(body);
                            item.comments = result.optInt("comments", item.comments);
                            item.likes = result.optInt("likes", item.likes);
                            item.saves = result.optInt("saves", item.saves);
                            item.shares = result.optInt("shares", item.shares);
                            item.views = result.optInt("views", item.views);
                            item.liked = result.optBoolean("liked", item.liked);
                            item.saved = result.optBoolean("saved", item.saved);
                        } catch (JSONException ignored) {
                        }
                    }
                    if (callback != null) callback.onResult(item, null);
                });
    }

    public void loadCreator(String username, CreatorCallback callback) {
        if (!isValidUsername(username)) {
            callback.onResult(null, restError(404, "Creator not found"));
            return;
        }
        ReelsBackendApi.request(account, "GET", "/api/creators/" + encode(username), null, null, true, (body, status) -> {
            if (status < 200 || status >= 300 || body == null) {
                callback.onResult(null, restError(status, "Creator request failed"));
                return;
            }
            try {
                final JSONObject result = new JSONObject(body);
                final CreatorInfo info = new CreatorInfo();
                info.userId = result.optLong("userId", result.optLong("id", 0));
                info.username = result.optString("username", username);
                info.displayName = result.optString("displayName", result.optString("name", ""));
                info.reels = result.optInt("reels", 0);
                info.followers = result.optInt("followers", 0);
                info.following = result.optInt("following", 0);
                info.isFollowing = result.optBoolean("isFollowing", false);
                info.isSelf = result.optBoolean("isSelf", false);
                callback.onResult(info, null);
            } catch (JSONException e) {
                callback.onResult(null, restError(status, "Invalid creator response"));
            }
        });
    }

    public static void setFollowing(int account, String username, boolean follow, FollowCallback callback) {
        if (!isValidUsername(username)) {
            callback.onResult(false, 0, 0, restError(404, "Creator not found"));
            return;
        }
        final String method = follow ? "PUT" : "DELETE";
        ReelsBackendApi.request(account, method, "/api/creators/" + encodePath(username) + "/follow",
                null, null, true, (body, status) -> {
                    if (status >= 200 && status < 300 && body != null) {
                        try {
                            final JSONObject result = new JSONObject(body);
                            callback.onResult(result.optBoolean("following", follow),
                                    result.optInt("followers", 0),
                                    result.optInt("followingCount", 0), null);
                            return;
                        } catch (JSONException ignored) {
                        }
                    }
                    callback.onResult(false, 0, 0, restError(status, "Follow request failed"));
                });
    }

    /** Public detail lookup used by /r/{id} deep links. */
    public static void loadReel(int account, String reelId, LinkCallback callback) {
        if (TextUtils.isEmpty(reelId)) {
            callback.onResult(null, LinkCallback.NOT_FOUND);
            return;
        }
        ReelsBackendApi.request(account, "GET", "/api/reels/" + encodePath(reelId), null, null, false, (body, status) -> {
            if (status >= 200 && status < 300 && body != null) {
                try {
                    JSONObject object = new JSONObject(body);
                    if (object.optJSONObject("reel") != null) object = object.optJSONObject("reel");
                    ReelsItem item = ReelsItem.fromProfile(account, object);
                    if (item == null && object != null) {
                        object = new JSONObject(object.toString());
                        if (TextUtils.isEmpty(object.optString("mediaUrl", ""))) {
                            object.put("mediaUrl", "/api/reels/" + encodePath(reelId) + "/media");
                        }
                        item = ReelsItem.fromProfile(account, object);
                    }
                    callback.onResult(item, item != null ? 0 : LinkCallback.NOT_FOUND);
                    return;
                } catch (JSONException ignored) {
                }
            }
            callback.onResult(null, status == 404 ? LinkCallback.NOT_FOUND : LinkCallback.NO_ACCESS);
        });
    }

    public void loadDiscussion(ReelsItem item, DiscussionCallback callback) {
        if (item == null || item.channelId == 0 || item.messageId <= 0) {
            callback.onResult(null, restError(0, "Channel video unavailable"));
            return;
        }
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
        if (item.source != ReelsItem.Source.CHANNEL || item.channelId == 0 || item.messageId <= 0) {
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

    private static String encodePath(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    private static boolean isAccessError(TLRPC.TL_error error) {
        if (error == null || error.text == null) {
            return false;
        }
        return error.text.startsWith("CHANNEL_PRIVATE") || error.text.startsWith("CHANNEL_PUBLIC_GROUP_NA")
                || error.text.startsWith("CHAT_FORBIDDEN") || error.text.startsWith("USER_BANNED_IN_CHANNEL");
    }
}
