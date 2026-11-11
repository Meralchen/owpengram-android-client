package org.telegram.ui.Components.Reels;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** REST data source for profile Reels. No channel lookup or MTProto message path lives here. */
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
    }

    private final int account;
    private ReelsFeedSource source = ReelsFeedSource.FOR_YOU;
    private String username;
    private String cursor;
    private int offset;
    private boolean endReached;
    private boolean loading;
    private int generation;

    private final HashMap<String, Boolean> likePending = new HashMap<>();
    private final HashSet<String> likeInFlight = new HashSet<>();
    private final HashMap<String, Boolean> savedPending = new HashMap<>();
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
        final String path = pagePath();
        if (path == null) {
            callback.onPage(null, false, new ReelsError(400, "A profile Reels source needs a username."));
            return;
        }
        final int requestGeneration = generation;
        loading = true;
        final boolean withProof = source.requiresAuth;
        ReelsBackendApi.request(account, "GET", path, null, null, withProof, (body, status) -> {
            if (requestGeneration != generation) {
                return;
            }
            loading = false;
            if (status < 200 || status >= 300) {
                callback.onPage(null, false, ReelsError.fromResponse(status, body));
                return;
            }
            try {
                final JSONObject root = new JSONObject(body == null ? "{}" : body);
                final ArrayList<ReelsItem> items = parseItems(root);
                final boolean end = advancePagination(root);
                callback.onPage(items, end, null);
            } catch (Exception malformed) {
                callback.onPage(null, false, new ReelsError(500, "The Reels feed could not be read."));
            }
        });
    }

    public void loadSingle(String id, ItemCallback callback) {
        if (TextUtils.isEmpty(id)) {
            callback.onItem(null, new ReelsError(400, "This Reel link is incomplete."));
            return;
        }
        final int requestGeneration = generation;
        final String path = "/api/reels/" + Uri.encode(id);
        ReelsBackendApi.request(account, "GET", path, null, null, false, (body, status) -> {
            if (requestGeneration != generation) {
                return;
            }
            finishSingle(body, status, callback);
        });
    }

    private void finishSingle(String body, int status, ItemCallback callback) {
        if (status < 200 || status >= 300) {
            callback.onItem(null, ReelsError.fromResponse(status, body));
            return;
        }
        try {
            final JSONObject root = new JSONObject(body == null ? "{}" : body);
            final JSONObject object = root.optJSONObject("item") != null ? root.optJSONObject("item") : root;
            final ReelsItem item = ReelsItem.fromJson(account, object);
            if (item == null) {
                callback.onItem(null, new ReelsError(404, "This Reel is no longer available."));
            } else {
                callback.onItem(item, null);
            }
        } catch (Exception malformed) {
            callback.onItem(null, new ReelsError(500, "This Reel could not be read."));
        }
    }

    public void refreshItem(ReelsItem item, ItemCallback callback) {
        if (item == null) return;
        loadSingle(item.id, (fresh, error) -> {
            if (fresh != null) {
                item.copyCountersFrom(fresh);
            }
            callback.onItem(item, error);
        });
    }

    public void loadCreator(String username, CreatorCallback callback) {
        if (TextUtils.isEmpty(username)) {
            callback.onCreator(null, new ReelsError(400, "Creator unavailable."));
            return;
        }
        final String path = "/api/creators/" + Uri.encode(username);
        ReelsBackendApi.request(account, "GET", path, null, null, true, (body, status) -> {
            if (status == 401) {
                ReelsBackendApi.request(account, "GET", path, null, null, false, (publicBody, publicStatus) ->
                        finishCreator(publicBody, publicStatus, callback));
            } else {
                finishCreator(body, status, callback);
            }
        });
    }

    private void finishCreator(String body, int status, CreatorCallback callback) {
        if (status < 200 || status >= 300) {
            callback.onCreator(null, ReelsError.fromResponse(status, body));
            return;
        }
        try {
            final JSONObject root = new JSONObject(body == null ? "{}" : body);
            final CreatorInfo info = new CreatorInfo();
            info.userId = root.optLong("userId", 0);
            info.username = root.optString("username", null);
            info.displayName = root.optString("displayName", info.username);
            info.reels = Math.max(0, root.optInt("reels", 0));
            info.followers = Math.max(0, root.optInt("followers", 0));
            info.following = Math.max(0, root.optInt("following", 0));
            info.isFollowing = root.optBoolean("isFollowing", false);
            info.isSelf = root.optBoolean("isSelf", false);
            callback.onCreator(info, null);
        } catch (Exception malformed) {
            callback.onCreator(null, new ReelsError(500, "Creator details could not be read."));
        }
    }

    public void setFollowing(String creatorUsername, boolean following, FollowCallback callback) {
        if (TextUtils.isEmpty(creatorUsername)) {
            callback.onFollow(null, new ReelsError(400, "Creator unavailable."));
            return;
        }
        if (!followInFlight.add(creatorUsername)) {
            return;
        }
        final String path = "/api/creators/" + Uri.encode(creatorUsername) + "/follow";
        ReelsBackendApi.request(account, following ? "PUT" : "DELETE", path, null, null, true, (body, status) -> {
            followInFlight.remove(creatorUsername);
            if (status < 200 || status >= 300) {
                callback.onFollow(null, ReelsError.fromResponse(status, body));
                return;
            }
            try {
                final JSONObject root = new JSONObject(body == null ? "{}" : body);
                final FollowResult result = new FollowResult();
                result.following = root.optBoolean("following", following);
                result.followers = root.optInt("followers", 0);
                result.followingCount = root.optInt("followingCount", 0);
                callback.onFollow(result, null);
            } catch (Exception malformed) {
                callback.onFollow(null, new ReelsError(500, "Follow state could not be read."));
            }
        });
    }

    public void setLiked(ReelsItem item, boolean liked, ItemActionCallback callback) {
        if (item == null) return;
        final boolean previousLiked = item.liked;
        final long previousLikes = item.likes;
        item.liked = liked;
        item.likes = Math.max(0, item.likes + (liked ? 1 : -1));
        if (!likeInFlight.add(item.id)) {
            likePending.put(item.id, liked);
            callback.onResult(item, null);
            return;
        }
        final JSONObject body = new JSONObject();
        try {
            body.put("liked", liked);
        } catch (Exception ignore) {
        }
        ReelsBackendApi.request(account, "PUT", "/api/reels/" + Uri.encode(item.id) + "/like",
                body, null, true, (response, status) -> {
                    ReelsError error = ReelsError.fromResponse(status, response);
                    if (error == null) {
                        try {
                            final JSONObject root = new JSONObject(response == null ? "{}" : response);
                            item.liked = root.optBoolean("liked", liked);
                            item.likes = Math.max(0, root.optLong("likes", item.likes));
                        } catch (Exception ignore) {
                        }
                    } else {
                        item.liked = previousLiked;
                        item.likes = previousLikes;
                    }
                    likeInFlight.remove(item.id);
                    callback.onResult(item, error);
                    final Boolean next = likePending.remove(item.id);
                    if (next != null && next != item.liked) {
                        setLiked(item, next, (nextItem, nextError) -> {
                        });
                    }
                });
    }

    public void setSaved(ReelsItem item, boolean saved, ItemActionCallback callback) {
        if (item == null) return;
        final boolean previous = item.saved;
        item.saved = saved;
        if (!savedInFlight.add(item.id)) {
            savedPending.put(item.id, saved);
            callback.onResult(item, null);
            return;
        }
        final String path = "/api/reels/" + Uri.encode(item.id) + "/save";
        final JSONObject body = new JSONObject();
        try {
            body.put("saved", saved);
        } catch (Exception ignore) {
        }
        ReelsBackendApi.request(account, saved ? "PUT" : "DELETE", path, saved ? body : null,
                null, true, (response, status) -> {
                    ReelsError error = ReelsError.fromResponse(status, response);
                    if (error == null) {
                        try {
                            final JSONObject root = new JSONObject(response == null ? "{}" : response);
                            item.saved = root.optBoolean("saved", saved);
                        } catch (Exception ignore) {
                        }
                    } else {
                        item.saved = previous;
                    }
                    savedInFlight.remove(item.id);
                    callback.onResult(item, error);
                    final Boolean next = savedPending.remove(item.id);
                    if (next != null && next != item.saved) {
                        setSaved(item, next, (nextItem, nextError) -> {
                        });
                    }
                });
    }

    public void reportView(ReelsItem item, long positionMs, boolean completed) {
        if (item == null) return;
        final JSONObject body = new JSONObject();
        try {
            body.put("positionMs", Math.max(0, positionMs));
            body.put("completed", completed);
        } catch (Exception ignore) {
        }
        ReelsBackendApi.request(account, "POST", "/api/reels/" + Uri.encode(item.id) + "/view",
                body, null, true, (response, status) -> {
                    if (status >= 200 && status < 300 && response != null) {
                        try {
                            final JSONObject root = new JSONObject(response);
                            item.views = Math.max(0, root.optLong("views", item.views));
                        } catch (Exception ignore) {
                        }
                    }
                });
    }

    public void reportShare(ReelsItem item) {
        if (item == null) return;
        item.shares = Math.max(0, item.shares + 1);
        ReelsBackendApi.request(account, "POST", "/api/reels/" + Uri.encode(item.id) + "/share",
                new JSONObject(), null, true, (response, status) -> {
                    if (status >= 200 && status < 300 && response != null) {
                        try {
                            final JSONObject root = new JSONObject(response);
                            item.shares = Math.max(0, root.optLong("shares", item.shares));
                        } catch (Exception ignore) {
                        }
                    }
                });
    }

    public void loadComments(ReelsItem item, Long before, CommentsCallback callback) {
        if (item == null) {
            callback.onComments(null, new ReelsError(400, "This Reel is unavailable."));
            return;
        }
        String path = "/api/reels/" + Uri.encode(item.id) + "/comments?limit=30";
        if (before != null && before > 0) {
            path += "&before=" + before;
        }
        final String finalPath = path;
        ReelsBackendApi.request(account, "GET", finalPath, null, null, true, (body, status) -> {
            if (status == 401) {
                ReelsBackendApi.request(account, "GET", finalPath, null, null, false, (publicBody, publicStatus) ->
                        finishComments(publicBody, publicStatus, callback));
            } else {
                finishComments(body, status, callback);
            }
        });
    }

    private void finishComments(String body, int status, CommentsCallback callback) {
        if (status < 200 || status >= 300) {
            callback.onComments(null, ReelsError.fromResponse(status, body));
            return;
        }
        try {
            final JSONObject root = new JSONObject(body == null ? "{}" : body);
            final CommentsPage page = new CommentsPage();
            final HashSet<Long> seen = new HashSet<>();
            addComments(root.optJSONArray("pinned"), page.pinned, seen, true);
            addComments(root.optJSONArray("items"), page.items, seen, false);
            page.nextBefore = nullableLong(root, "nextBefore");
            callback.onComments(page, null);
        } catch (Exception malformed) {
            callback.onComments(null, new ReelsError(500, "Comments could not be read."));
        }
    }

    public void loadReplies(ReelsItem item, long commentId, Long after, RepliesCallback callback) {
        if (item == null || commentId <= 0) {
            callback.onReplies(null, new ReelsError(400, "This comment is unavailable."));
            return;
        }
        String path = "/api/reels/" + Uri.encode(item.id) + "/comments/" + commentId
                + "/replies?limit=30&after=" + (after == null ? 0 : after);
        final String finalPath = path;
        ReelsBackendApi.request(account, "GET", finalPath, null, null, true, (body, status) -> {
            if (status == 401) {
                ReelsBackendApi.request(account, "GET", finalPath, null, null, false, (publicBody, publicStatus) ->
                        finishReplies(publicBody, publicStatus, callback));
            } else {
                finishReplies(body, status, callback);
            }
        });
    }

    private void finishReplies(String body, int status, RepliesCallback callback) {
        if (status < 200 || status >= 300) {
            callback.onReplies(null, ReelsError.fromResponse(status, body));
            return;
        }
        try {
            final JSONObject root = new JSONObject(body == null ? "{}" : body);
            final RepliesPage page = new RepliesPage();
            final HashSet<Long> seen = new HashSet<>();
            addComments(root.optJSONArray("items"), page.items, seen, false);
            page.nextAfter = nullableLong(root, "nextAfter");
            callback.onReplies(page, null);
        } catch (Exception malformed) {
            callback.onReplies(null, new ReelsError(500, "Replies could not be read."));
        }
    }

    public void postComment(ReelsItem item, String text, long parentId, String gifId, String gifUrl,
                            CommentCallback callback) {
        if (item == null) {
            callback.onComment(null, new ReelsError(400, "This Reel is unavailable."));
            return;
        }
        final JSONObject body = new JSONObject();
        try {
            body.put("text", text == null ? "" : text);
            body.put("parentId", parentId);
            if (!TextUtils.isEmpty(gifId)) {
                body.put("gifId", gifId);
            } else if (!TextUtils.isEmpty(gifUrl)) {
                body.put("gifUrl", gifUrl);
            } else {
                body.put("gifId", JSONObject.NULL);
            }
        } catch (Exception ignore) {
        }
        ReelsBackendApi.request(account, "POST", "/api/reels/" + Uri.encode(item.id) + "/comments",
                body, null, true, (response, status) -> {
                    if (status < 200 || status >= 300) {
                        callback.onComment(null, ReelsError.fromResponse(status, response));
                        return;
                    }
                    try {
                        final JSONObject root = new JSONObject(response == null ? "{}" : response);
                        final JSONObject object = root.optJSONObject("comment") != null
                                ? root.optJSONObject("comment") : root;
                        callback.onComment(ReelsComment.fromJson(account, object), null);
                    } catch (Exception malformed) {
                        callback.onComment(null, new ReelsError(500, "The comment could not be read."));
                    }
                });
    }

    public void uploadGif(byte[] bytes, GifCallback callback) {
        ReelsBackendApi.uploadCommentGif(account, bytes, (body, status) -> {
            if (status < 200 || status >= 300) {
                callback.onGif(null, ReelsError.fromResponse(status, body));
                return;
            }
            try {
                final JSONObject root = new JSONObject(body == null ? "{}" : body);
                final JSONObject object = root.optJSONObject("gif") != null ? root.optJSONObject("gif") : root;
                final GifUpload gif = new GifUpload();
                gif.id = object.optString("id", object.optString("gifId", null));
                gif.url = ReelsBackendApi.absoluteUrl(account, object.optString("url", null));
                gif.width = object.optInt("width", 0);
                gif.height = object.optInt("height", 0);
                if (TextUtils.isEmpty(gif.id) && TextUtils.isEmpty(gif.url)) {
                    callback.onGif(null, new ReelsError(500, "The GIF could not be uploaded."));
                } else {
                    callback.onGif(gif, null);
                }
            } catch (Exception malformed) {
                callback.onGif(null, new ReelsError(500, "The GIF could not be read."));
            }
        });
    }

    public void deleteComment(ReelsItem item, long commentId, ActionCallback callback) {
        if (item == null || commentId <= 0) {
            callback.onResult(new ReelsError(400, "This comment is unavailable."));
            return;
        }
        ReelsBackendApi.request(account, "DELETE", "/api/reels/" + Uri.encode(item.id)
                + "/comments/" + commentId, null, null, true, (body, status) ->
                callback.onResult(ReelsError.fromResponse(status, body)));
    }

    public void likeComment(ReelsItem item, ReelsComment comment, boolean liked, CommentActionCallback callback) {
        if (item == null || comment == null) {
            callback.onResult(null, new ReelsError(400, "This comment is unavailable."));
            return;
        }
        final boolean previousLiked = comment.liked;
        final long previousLikes = comment.likes;
        comment.liked = liked;
        comment.likes = Math.max(0, comment.likes + (liked ? 1 : -1));
        final JSONObject body = new JSONObject();
        try {
            body.put("liked", liked);
        } catch (Exception ignore) {
        }
        ReelsBackendApi.request(account, "PUT", "/api/reels/" + Uri.encode(item.id)
                + "/comments/" + comment.id + "/like", body, null, true, (response, status) -> {
                    final ReelsError error = ReelsError.fromResponse(status, response);
                    if (error == null) {
                        try {
                            final JSONObject root = new JSONObject(response == null ? "{}" : response);
                            comment.liked = root.optBoolean("liked", liked);
                            comment.likes = Math.max(0, root.optLong("likes", comment.likes));
                        } catch (Exception ignore) {
                        }
                    } else {
                        comment.liked = previousLiked;
                        comment.likes = previousLikes;
                    }
                    callback.onResult(comment, error);
                });
    }

    public void pinComment(ReelsItem item, ReelsComment comment, boolean pinned, CommentActionCallback callback) {
        if (item == null || comment == null) {
            callback.onResult(null, new ReelsError(400, "This comment is unavailable."));
            return;
        }
        final boolean previous = comment.pinned;
        comment.pinned = pinned;
        final JSONObject body = new JSONObject();
        try {
            body.put("pinned", pinned);
        } catch (Exception ignore) {
        }
        ReelsBackendApi.request(account, "PUT", "/api/reels/" + Uri.encode(item.id)
                + "/comments/" + comment.id + "/pin", body, null, true, (response, status) -> {
                    final ReelsError error = ReelsError.fromResponse(status, response);
                    if (error != null) {
                        comment.pinned = previous;
                    } else {
                        try {
                            final JSONObject root = new JSONObject(response == null ? "{}" : response);
                            comment.pinned = root.optBoolean("pinned", pinned);
                        } catch (Exception ignore) {
                        }
                    }
                    callback.onResult(comment, error);
                });
    }

    public void cancel() {
        generation++;
        loading = false;
    }

    private String pagePath() {
        switch (source) {
            case FOR_YOU:
            case LATEST:
            case FOLLOWING:
                return "/api/reels/feed?mode=" + source.mode + "&limit=20"
                        + (TextUtils.isEmpty(cursor) ? "" : "&cursor=" + Uri.encode(cursor));
            case PROFILE:
                if (TextUtils.isEmpty(username)) return null;
                return "/api/profiles/" + Uri.encode(username) + "/reels?limit=20&offset=" + offset;
            case SAVED:
                return "/api/reels/saved?limit=20&offset=" + offset;
            case SINGLE:
            default:
                return null;
        }
    }

    private ArrayList<ReelsItem> parseItems(JSONObject root) {
        final ArrayList<ReelsItem> result = new ArrayList<>();
        final JSONArray items = root.optJSONArray("items");
        if (items == null) return result;
        for (int i = 0; i < items.length(); i++) {
            final ReelsItem item = ReelsItem.fromJson(account, items.optJSONObject(i));
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }

    private boolean advancePagination(JSONObject root) {
        if (source.isGlobalFeed()) {
            final String next = root.has("nextCursor") && !root.isNull("nextCursor")
                    ? root.optString("nextCursor", null) : null;
            cursor = next;
            return TextUtils.isEmpty(next);
        }
        final int next = root.optInt("nextOffset", -1);
        if (next < 0 || next <= offset) {
            return true;
        }
        offset = next;
        return false;
    }

    private void addComments(JSONArray array, ArrayList<ReelsComment> target, HashSet<Long> seen,
                             boolean pinned) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            final ReelsComment comment = ReelsComment.fromJson(account, array.optJSONObject(i));
            if (comment == null || !seen.add(comment.id)) continue;
            if (pinned) comment.pinned = true;
            target.add(comment);
        }
    }

    private static Long nullableLong(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return null;
        final long value = object.optLong(key, 0);
        return value > 0 ? value : null;
    }
}
