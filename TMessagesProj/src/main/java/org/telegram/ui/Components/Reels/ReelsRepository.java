package org.telegram.ui.Components.Reels;

import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_reels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Reels data source. Everything goes through the account's MTProto session via
 * {@code reels.*}; there is no HTTP transport and no channel lookup here.
 */
public final class ReelsRepository {

    public interface PageCallback {
        void onPage(List<ReelsItem> items, boolean endReached, ReelsError error);
    }

    public interface ItemCallback {
        void onItem(ReelsItem item, ReelsError error);
    }

    public interface CreatorCallback {
        void onCreator(CreatorInfo creator, ReelsError error);
    }

    public interface FollowCallback {
        void onFollow(FollowResult result, ReelsError error);
    }

    public interface ItemActionCallback {
        void onResult(ReelsItem item, ReelsError error);
    }

    public interface CommentsCallback {
        void onComments(CommentsPage page, ReelsError error);
    }

    public interface RepliesCallback {
        void onReplies(RepliesPage page, ReelsError error);
    }

    public interface CommentCallback {
        void onComment(ReelsComment comment, ReelsError error);
    }

    public interface CommentActionCallback {
        void onResult(ReelsComment comment, ReelsError error);
    }

    public interface ActionCallback {
        void onResult(ReelsError error);
    }

    public interface GifCallback {
        void onGif(GifUpload gif, ReelsError error);
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

    public static final class FollowResult {
        public boolean following;
        public int followers;
        public int followingCount;
    }

    public static final class CommentsPage {
        public final ArrayList<ReelsComment> pinned = new ArrayList<>();
        public final ArrayList<ReelsComment> items = new ArrayList<>();
        public Long nextBefore;
    }

    public static final class RepliesPage {
        public final ArrayList<ReelsComment> items = new ArrayList<>();
        public Long nextAfter;
    }

    public static final class GifUpload {
        public String id;
        public String url;
        public int width;
        public int height;
        /** Durable document reference sent with reels.sendComment. */
        public TLRPC.InputDocument input;
    }

    private static final int PAGE_SIZE = 20;
    private static final int COMMENT_PAGE_SIZE = 30;

    private final int account;
    private ReelsFeedSource source = ReelsFeedSource.FOR_YOU;
    private String username;
    private String cursor;
    private int offset;
    private boolean endReached;
    private boolean loading;
    private int generation;

    private final HashMap<String, TLRPC.User> creatorUsers = new HashMap<>();
    private final HashSet<String> likeInFlight = new HashSet<>();
    private final HashSet<String> savedInFlight = new HashSet<>();
    private final HashSet<String> followInFlight = new HashSet<>();

    public ReelsRepository(int account) {
        this.account = account;
    }

    public void setSource(ReelsFeedSource source, String username) {
        generation++;
        this.source = source == null ? ReelsFeedSource.FOR_YOU : source;
        this.username = username;
        cursor = null;
        offset = 0;
        endReached = false;
        loading = false;
    }

    public ReelsFeedSource getSource() {
        return source;
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean isEndReached() {
        return endReached;
    }

    public void loadNextPage(PageCallback callback) {
        if (source == ReelsFeedSource.SINGLE) {
            callback.onPage(new ArrayList<>(), true, null);
            return;
        }
        if (loading || endReached) {
            return;
        }
        final int requestGeneration = generation;
        loading = true;
        final TLObject request;
        switch (source) {
            case PROFILE: {
                if (TextUtils.isEmpty(username)) {
                    loading = false;
                    callback.onPage(null, false, new ReelsError(400, "A profile Reels source needs a username."));
                    return;
                }
                final TL_reels.getCreatorReels profile = new TL_reels.getCreatorReels();
                profile.username = username;
                profile.offset = String.valueOf(offset);
                profile.limit = PAGE_SIZE;
                request = profile;
                break;
            }
            case SAVED: {
                final TL_reels.getSaved saved = new TL_reels.getSaved();
                saved.offset = String.valueOf(offset);
                saved.limit = PAGE_SIZE;
                request = saved;
                break;
            }
            case FOR_YOU:
            case LATEST:
            case FOLLOWING:
            default: {
                final TL_reels.getFeed feed = new TL_reels.getFeed();
                feed.for_you = source == ReelsFeedSource.FOR_YOU;
                feed.latest = source == ReelsFeedSource.LATEST;
                feed.following = source == ReelsFeedSource.FOLLOWING;
                feed.offset = cursor == null ? "" : cursor;
                feed.limit = PAGE_SIZE;
                request = feed;
                break;
            }
        }
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (requestGeneration != generation) {
                return;
            }
            loading = false;
            final ReelsError failure = ReelsError.fromTl(error);
            if (failure != null) {
                callback.onPage(null, false, failure);
                return;
            }
            if (!(response instanceof TL_reels.ReelsFeed)) {
                callback.onPage(null, false, new ReelsError(500, "The Reels feed could not be read."));
                return;
            }
            final TL_reels.ReelsFeed feed = (TL_reels.ReelsFeed) response;
            if (feed instanceof TL_reels.TL_reels_feedNotModified) {
                endReached = true;
                callback.onPage(new ArrayList<>(), true, null);
                return;
            }
            final boolean end = advancePagination(feed);
            callback.onPage(toItems(feed), end, null);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void loadSingle(String id, ItemCallback callback) {
        final long reelId;
        try {
            reelId = Long.parseLong(id);
        } catch (Exception invalid) {
            callback.onItem(null, new ReelsError(400, "This Reel link is incomplete."));
            return;
        }
        final int requestGeneration = generation;
        final TL_reels.getReel request = new TL_reels.getReel();
        request.id = reelId;
        // Zero means "resolve by id": share links carry no access hash yet.
        request.access_hash = 0;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (requestGeneration != generation) {
                return;
            }
            final ReelsError failure = ReelsError.fromTl(error);
            if (failure != null) {
                callback.onItem(null, failure);
                return;
            }
            if (!(response instanceof TL_reels.Reel)) {
                callback.onItem(null, new ReelsError(404, "This Reel is no longer available."));
                return;
            }
            final TL_reels.Reel reel = (TL_reels.Reel) response;
            final ReelsItem item = ReelsItem.fromTl(account, reel, ownerFromCache(reel.owner_id));
            if (item == null) {
                callback.onItem(null, new ReelsError(404, "This Reel is no longer available."));
            } else {
                callback.onItem(item, null);
            }
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void refreshItem(ReelsItem item, ItemCallback callback) {
        if (item == null || item.tlId == 0) return;
        final TL_reels.getReel request = new TL_reels.getReel();
        request.id = item.tlId;
        request.access_hash = item.accessHash;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            final ReelsError failure = ReelsError.fromTl(error);
            if (failure == null && response instanceof TL_reels.Reel) {
                final ReelsItem fresh = ReelsItem.fromTl(account, (TL_reels.Reel) response, ownerFromCache(item.ownerUserId));
                if (fresh != null) {
                    item.copyCountersFrom(fresh);
                }
            }
            callback.onItem(item, failure);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void loadCreator(String username, CreatorCallback callback) {
        if (TextUtils.isEmpty(username)) {
            callback.onCreator(null, new ReelsError(400, "Creator unavailable."));
            return;
        }
        final TL_reels.getCreator request = new TL_reels.getCreator();
        request.username = username;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            final ReelsError failure = ReelsError.fromTl(error);
            if (failure != null) {
                callback.onCreator(null, failure);
                return;
            }
            if (!(response instanceof TL_reels.ReelsCreator)) {
                callback.onCreator(null, new ReelsError(500, "Creator details could not be read."));
                return;
            }
            final TL_reels.ReelsCreator creator = (TL_reels.ReelsCreator) response;
            final CreatorInfo info = new CreatorInfo();
            if (creator.user != null) {
                creatorUsers.put(username, creator.user);
                info.userId = creator.user.id;
                info.username = creator.user.username;
                info.displayName = displayName(creator.user);
            }
            info.reels = Math.max(0, creator.reels);
            info.followers = Math.max(0, creator.followers);
            info.following = Math.max(0, creator.following);
            info.isFollowing = creator.is_following;
            info.isSelf = creator.is_self;
            callback.onCreator(info, null);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void setFollowing(String creatorUsername, boolean following, FollowCallback callback) {
        if (TextUtils.isEmpty(creatorUsername)) {
            callback.onFollow(null, new ReelsError(400, "Creator unavailable."));
            return;
        }
        if (!followInFlight.add(creatorUsername)) {
            return;
        }
        final TLRPC.User creator = creatorUsers.get(creatorUsername);
        if (creator == null) {
            followInFlight.remove(creatorUsername);
            loadCreator(creatorUsername, (info, error) -> {
                if (info == null) {
                    callback.onFollow(null, error);
                    return;
                }
                setFollowing(creatorUsername, following, callback);
            });
            return;
        }
        final TL_reels.follow request = new TL_reels.follow();
        request.user = MessagesController.getInstance(account).getInputUser(creator);
        request.following = following;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            followInFlight.remove(creatorUsername);
            final ReelsError failure = ReelsError.fromTl(error);
            if (failure != null) {
                callback.onFollow(null, failure);
                return;
            }
            if (!(response instanceof TL_reels.ReelsCreator)) {
                callback.onFollow(null, new ReelsError(500, "Follow state could not be read."));
                return;
            }
            final TL_reels.ReelsCreator result = (TL_reels.ReelsCreator) response;
            final FollowResult out = new FollowResult();
            out.following = result.is_following;
            out.followers = result.followers;
            out.followingCount = result.following;
            callback.onFollow(out, null);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void setLiked(ReelsItem item, boolean liked, ItemActionCallback callback) {
        if (item == null || item.tlId == 0) return;
        final boolean previousLiked = item.liked;
        final long previousLikes = item.likes;
        item.liked = liked;
        item.likes = Math.max(0, item.likes + (liked ? 1 : -1));
        if (!likeInFlight.add(item.id)) {
            callback.onResult(item, null);
            return;
        }
        final TL_reels.like request = new TL_reels.like();
        request.id = item.tlId;
        request.access_hash = item.accessHash;
        request.liked = liked;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            likeInFlight.remove(item.id);
            final ReelsError failure = ReelsError.fromTl(error);
            if (failure == null && response instanceof TL_reels.Reel) {
                final TL_reels.Reel updated = (TL_reels.Reel) response;
                item.liked = updated.liked;
                item.likes = Math.max(0, updated.likes);
                item.comments = Math.max(0, updated.comments);
            } else {
                item.liked = previousLiked;
                item.likes = previousLikes;
            }
            callback.onResult(item, failure);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void setSaved(ReelsItem item, boolean saved, ItemActionCallback callback) {
        if (item == null || item.tlId == 0) return;
        final boolean previous = item.saved;
        item.saved = saved;
        if (!savedInFlight.add(item.id)) {
            callback.onResult(item, null);
            return;
        }
        final TL_reels.save request = new TL_reels.save();
        request.id = item.tlId;
        request.access_hash = item.accessHash;
        request.saved = saved;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            savedInFlight.remove(item.id);
            final ReelsError failure = ReelsError.fromTl(error);
            if (failure == null && response instanceof TL_reels.Reel) {
                item.saved = ((TL_reels.Reel) response).saved;
                item.saves = Math.max(0, ((TL_reels.Reel) response).saves);
            } else {
                item.saved = previous;
            }
            callback.onResult(item, failure);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void reportView(ReelsItem item, long positionMs, boolean completed) {
        if (item == null || item.tlId == 0) return;
        final TL_reels.view request = new TL_reels.view();
        request.id = item.tlId;
        request.access_hash = item.accessHash;
        request.position_ms = (int) Math.max(0, Math.min(Integer.MAX_VALUE, positionMs));
        request.completed = completed;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
        }, ConnectionsManager.RequestFlagDoNotWaitFloodWait);
    }

    public void reportShare(ReelsItem item) {
        if (item == null || item.tlId == 0) return;
        item.shares = Math.max(0, item.shares + 1);
        final TL_reels.share request = new TL_reels.share();
        request.id = item.tlId;
        request.access_hash = item.accessHash;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (response instanceof TL_reels.Reel) {
                item.shares = Math.max(0, ((TL_reels.Reel) response).shares);
            }
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void loadComments(ReelsItem item, Long before, CommentsCallback callback) {
        if (item == null || item.tlId == 0) {
            callback.onComments(null, new ReelsError(400, "This Reel is unavailable."));
            return;
        }
        final TL_reels.getComments request = new TL_reels.getComments();
        request.id = item.tlId;
        request.access_hash = item.accessHash;
        request.offset = before == null ? 0 : (int) Math.min(Integer.MAX_VALUE, before);
        request.limit = COMMENT_PAGE_SIZE;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            final ReelsError failure = ReelsError.fromTl(error);
            if (failure != null) {
                callback.onComments(null, failure);
                return;
            }
            if (!(response instanceof TL_reels.ReelsComments)) {
                callback.onComments(null, new ReelsError(500, "Comments could not be read."));
                return;
            }
            final TL_reels.ReelsComments page = (TL_reels.ReelsComments) response;
            final HashMap<Long, TLRPC.User> users = usersById(page.users);
            final CommentsPage out = new CommentsPage();
            final HashSet<Long> seen = new HashSet<>();
            for (TL_reels.ReelComment comment : page.comments) {
                final ReelsComment mapped = ReelsComment.fromTl(account, comment, users.get(comment.user_id));
                if (mapped == null || !seen.add(mapped.id)) continue;
                if (comment.pinned) {
                    out.pinned.add(mapped);
                } else {
                    out.items.add(mapped);
                }
            }
            out.nextBefore = page.next_offset > 0 ? (long) page.next_offset : null;
            callback.onComments(out, null);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void loadReplies(ReelsItem item, long commentId, Long after, RepliesCallback callback) {
        if (item == null || item.tlId == 0 || commentId <= 0) {
            callback.onReplies(null, new ReelsError(400, "This comment is unavailable."));
            return;
        }
        final TL_reels.getCommentReplies request = new TL_reels.getCommentReplies();
        request.id = item.tlId;
        request.access_hash = item.accessHash;
        request.comment_id = commentId;
        request.offset = after == null ? 0 : (int) Math.min(Integer.MAX_VALUE, after);
        request.limit = COMMENT_PAGE_SIZE;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            final ReelsError failure = ReelsError.fromTl(error);
            if (failure != null) {
                callback.onReplies(null, failure);
                return;
            }
            if (!(response instanceof TL_reels.ReelsComments)) {
                callback.onReplies(null, new ReelsError(500, "Replies could not be read."));
                return;
            }
            final TL_reels.ReelsComments page = (TL_reels.ReelsComments) response;
            final HashMap<Long, TLRPC.User> users = usersById(page.users);
            final RepliesPage out = new RepliesPage();
            final HashSet<Long> seen = new HashSet<>();
            for (TL_reels.ReelComment comment : page.comments) {
                final ReelsComment mapped = ReelsComment.fromTl(account, comment, users.get(comment.user_id));
                if (mapped != null && seen.add(mapped.id)) {
                    out.items.add(mapped);
                }
            }
            out.nextAfter = page.next_offset > 0 ? (long) page.next_offset : null;
            callback.onReplies(out, null);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void postComment(ReelsItem item, String text, long parentId, String gifId, String gifUrl,
                            CommentCallback callback) {
        postComment(item, text, parentId, null, callback);
    }

    public void postComment(ReelsItem item, String text, long parentId, TLRPC.InputDocument gif,
                            CommentCallback callback) {
        if (item == null || item.tlId == 0) {
            callback.onComment(null, new ReelsError(400, "This Reel is unavailable."));
            return;
        }
        if (!item.commentsEnabled) {
            callback.onComment(null, new ReelsError(403, "Comments are turned off."));
            return;
        }
        final TL_reels.sendComment request = new TL_reels.sendComment();
        request.id = item.tlId;
        request.access_hash = item.accessHash;
        request.reply_to = Math.max(0, parentId);
        request.text = text == null ? "" : text;
        request.setGif(gif);
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            final ReelsError failure = ReelsError.fromTl(error);
            if (failure != null) {
                callback.onComment(null, failure);
                return;
            }
            if (!(response instanceof TL_reels.ReelComment)) {
                callback.onComment(null, new ReelsError(500, "The comment could not be read."));
                return;
            }
            final TL_reels.ReelComment comment = (TL_reels.ReelComment) response;
            callback.onComment(ReelsComment.fromTl(account, comment, ownerCommentUser(comment.user_id)), null);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void uploadGif(byte[] bytes, GifCallback callback) {
        ReelsGifUploader.upload(account, bytes, callback);
    }

    public void deleteComment(ReelsItem item, long commentId, ActionCallback callback) {
        if (item == null || item.tlId == 0 || commentId <= 0) {
            callback.onResult(new ReelsError(400, "This comment is unavailable."));
            return;
        }
        final TL_reels.deleteComment request = new TL_reels.deleteComment();
        request.id = item.tlId;
        request.access_hash = item.accessHash;
        request.comment_id = commentId;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) ->
                callback.onResult(ReelsError.fromTl(error)), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void likeComment(ReelsItem item, ReelsComment comment, boolean liked, CommentActionCallback callback) {
        if (item == null || item.tlId == 0 || comment == null) {
            callback.onResult(null, new ReelsError(400, "This comment is unavailable."));
            return;
        }
        final boolean previousLiked = comment.liked;
        final long previousLikes = comment.likes;
        comment.liked = liked;
        comment.likes = Math.max(0, comment.likes + (liked ? 1 : -1));
        final TL_reels.likeComment request = new TL_reels.likeComment();
        request.id = item.tlId;
        request.access_hash = item.accessHash;
        request.comment_id = comment.id;
        request.liked = liked;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            final ReelsError failure = ReelsError.fromTl(error);
            if (failure == null && response instanceof TL_reels.ReelComment) {
                final TL_reels.ReelComment updated = (TL_reels.ReelComment) response;
                comment.liked = updated.liked;
                comment.likes = Math.max(0, updated.likes);
            } else {
                comment.liked = previousLiked;
                comment.likes = previousLikes;
            }
            callback.onResult(comment, failure);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void pinComment(ReelsItem item, ReelsComment comment, boolean pinned, CommentActionCallback callback) {
        if (item == null || item.tlId == 0 || comment == null) {
            callback.onResult(null, new ReelsError(400, "This comment is unavailable."));
            return;
        }
        final boolean previous = comment.pinned;
        comment.pinned = pinned;
        final TL_reels.pinComment request = new TL_reels.pinComment();
        request.id = item.tlId;
        request.access_hash = item.accessHash;
        request.comment_id = comment.id;
        request.pinned = pinned;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            final ReelsError failure = ReelsError.fromTl(error);
            if (failure != null) {
                comment.pinned = previous;
            } else if (response instanceof TL_reels.ReelComment) {
                comment.pinned = ((TL_reels.ReelComment) response).pinned;
            }
            callback.onResult(comment, failure);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void cancel() {
        generation++;
        loading = false;
    }

    private boolean advancePagination(TL_reels.ReelsFeed feed) {
        if (source.isGlobalFeed()) {
            final String next = TextUtils.isEmpty(feed.next_offset) ? null : feed.next_offset;
            cursor = next;
            return TextUtils.isEmpty(next);
        }
        final int next;
        try {
            next = Integer.parseInt(feed.next_offset);
        } catch (Exception invalid) {
            return true;
        }
        if (next <= offset) {
            return true;
        }
        offset = next;
        return false;
    }

    private ArrayList<ReelsItem> toItems(TL_reels.ReelsFeed feed) {
        final ArrayList<ReelsItem> result = new ArrayList<>();
        final HashMap<Long, TLRPC.User> users = usersById(feed.users);
        for (TL_reels.Reel reel : feed.reels) {
            final TLRPC.User owner = users.get(reel.owner_id);
            if (owner != null) {
                creatorUsers.put(owner.username, owner);
            }
            final ReelsItem item = ReelsItem.fromTl(account, reel, owner);
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }

    private TLRPC.User ownerFromCache(long userId) {
        return MessagesController.getInstance(account).getUser(userId);
    }

    private TLRPC.User ownerCommentUser(long userId) {
        return MessagesController.getInstance(account).getUser(userId);
    }

    private static HashMap<Long, TLRPC.User> usersById(ArrayList<TLRPC.User> users) {
        final HashMap<Long, TLRPC.User> map = new HashMap<>();
        if (users == null) return map;
        for (TLRPC.User user : users) {
            if (user != null) {
                map.put(user.id, user);
            }
        }
        return map;
    }

    private static String displayName(TLRPC.User user) {
        final StringBuilder builder = new StringBuilder();
        if (!TextUtils.isEmpty(user.first_name)) {
            builder.append(user.first_name);
        }
        if (!TextUtils.isEmpty(user.last_name)) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(user.last_name);
        }
        return builder.length() == 0 ? user.username : builder.toString();
    }
}
