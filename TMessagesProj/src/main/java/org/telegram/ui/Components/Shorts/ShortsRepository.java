package org.telegram.ui.Components.Shorts;

import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Feed pages (messages.searchGlobal + inputMessagesFilterVideo), heart reactions
 * (messages.sendReaction) and discussions (getDiscussionMessage / getReplies),
 * all through the account's MTProto connection.
 */
public final class ShortsRepository {

    public static final int PAGE_SIZE = 20;

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,31}");

    public interface FeedCallback {
        void onPage(ArrayList<ShortsItem> items, boolean endReached, TLRPC.TL_error error);
    }

    public interface ReactionCallback {
        void onResult(ShortsItem item, TLRPC.TL_error error);
    }

    public interface LinkCallback {
        int NOT_FOUND = 1;
        int NO_ACCESS = 2;

        void onResult(ShortsItem item, int failure);
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

    private int offsetRate;
    private TLRPC.InputPeer offsetPeer = new TLRPC.TL_inputPeerEmpty();
    private int offsetId;
    private boolean loading;
    private boolean endReached;
    private int feedRequestId;

    /** Clip -> desired liked state while a sendReaction for it is in flight. */
    private final SparseArray<Boolean> pendingLikes = new SparseArray<>();
    private final SparseArray<Boolean> inFlightLikes = new SparseArray<>();

    public ShortsRepository(int account) {
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

    /** Starts the chronological feed right after the linked clip, so it stays the current page. */
    public void startAfter(ShortsItem item) {
        offsetRate = item.getMessage().date;
        offsetPeer = controller().getInputPeer(item.getDialogId());
        offsetId = item.messageId;
        endReached = false;
    }

    public void loadNextPage(FeedCallback callback) {
        if (loading || endReached) {
            return;
        }
        loading = true;
        final TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
        req.q = "";
        req.filter = new TLRPC.TL_inputMessagesFilterVideo();
        req.limit = PAGE_SIZE;
        req.offset_rate = offsetRate;
        req.offset_peer = offsetPeer;
        req.offset_id = offsetId;
        feedRequestId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            loading = false;
            feedRequestId = 0;
            if (!(response instanceof TLRPC.messages_Messages)) {
                callback.onPage(new ArrayList<>(), endReached, error);
                return;
            }
            final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            controller().putUsers(res.users, false);
            controller().putChats(res.chats, false);
            final ArrayList<ShortsItem> items = new ArrayList<>();
            for (int i = 0; i < res.messages.size(); i++) {
                final ShortsItem item = ShortsItem.from(account, res.messages.get(i));
                if (item != null) {
                    item.pageRate = res.next_rate;
                    items.add(item);
                }
            }
            if (res.messages.isEmpty()) {
                endReached = true;
            } else {
                final TLRPC.Message last = res.messages.get(res.messages.size() - 1);
                offsetRate = res.next_rate != 0 ? res.next_rate : last.date;
                offsetPeer = controller().getInputPeer(MessageObject.getPeerId(last.peer_id));
                offsetId = last.id;
                endReached = !(res instanceof TLRPC.TL_messages_messagesSlice);
            }
            callback.onPage(items, endReached, null);
        }));
    }

    /**
     * Optimistic heart toggle. Requests per clip are serialized: taps while a request is in
     * flight only update the desired state, which is sent once the previous request returns.
     */
    public void setLiked(ShortsItem item, boolean liked, ReactionCallback callback) {
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

    private void sendReaction(ShortsItem item, boolean liked, ReactionCallback callback) {
        inFlightLikes.put(item.messageId, liked);
        final TLRPC.TL_messages_sendReaction req = new TLRPC.TL_messages_sendReaction();
        req.peer = controller().getInputPeer(item.getDialogId());
        req.msg_id = item.messageId;
        req.flags |= 1;
        if (liked) {
            final TLRPC.TL_reactionEmoji heart = new TLRPC.TL_reactionEmoji();
            heart.emoticon = ShortsItem.HEART;
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

    public boolean hasPendingReaction(ShortsItem item) {
        return inFlightLikes.get(item.messageId) != null;
    }

    public void loadDiscussion(ShortsItem item, DiscussionCallback callback) {
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

    public void exportLink(ShortsItem item, Utilities.Callback<String> callback) {
        final TLRPC.TL_channels_exportMessageLink req = new TLRPC.TL_channels_exportMessageLink();
        req.channel = controller().getInputChannel(item.channelId);
        req.id = item.messageId;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() ->
                callback.run(response instanceof TLRPC.TL_exportedMessageLink ? ((TLRPC.TL_exportedMessageLink) response).link : null)));
    }

    public void cancel() {
        if (feedRequestId != 0) {
            ConnectionsManager.getInstance(account).cancelRequest(feedRequestId, true);
            feedRequestId = 0;
            loading = false;
        }
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
                    final ShortsItem item = ShortsItem.from(account, m);
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
