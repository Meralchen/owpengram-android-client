package org.telegram.ui.Components.Reels;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** A permanent, profile-owned Reel. There is deliberately no channel or message identity here. */
public final class ReelsItem {

    public final String id;
    public final String ownerUsername;
    public final String ownerDisplayName;
    public final long ownerUserId;
    public final String mediaUrl;
    public final String thumbnailUrl;
    public final String shareUrl;
    public final String caption;
    public final int durationSeconds;
    public final int width;
    public final int height;
    public final long createdAtMs;
    public final boolean commentsEnabled;

    public long views;
    public long likes;
    public long comments;
    public long saves;
    public long shares;
    public boolean liked;
    public boolean saved;
    public boolean following;
    public long resumePositionMs;

    private ReelsItem(String id, String ownerUsername, String ownerDisplayName, long ownerUserId,
                      String mediaUrl, String thumbnailUrl, String shareUrl, String caption,
                      int durationSeconds, int width, int height, long createdAtMs, boolean commentsEnabled) {
        this.id = id;
        this.ownerUsername = ownerUsername;
        this.ownerDisplayName = ownerDisplayName;
        this.ownerUserId = ownerUserId;
        this.mediaUrl = mediaUrl;
        this.thumbnailUrl = thumbnailUrl;
        this.shareUrl = shareUrl;
        this.caption = caption;
        this.durationSeconds = durationSeconds;
        this.width = width;
        this.height = height;
        this.createdAtMs = createdAtMs;
        this.commentsEnabled = commentsEnabled;
    }

    public static ReelsItem fromJson(int account, JSONObject object) {
        if (object == null) {
            return null;
        }
        final String id = object.optString("id", null);
        if (TextUtils.isEmpty(id)) {
            return null;
        }
        final String source = object.optString("source", "profile");
        if (!"profile".equals(source) || id.startsWith("channel:")) {
            FileLog.w("Ignoring non-profile Reel " + id);
            return null;
        }

        final String mediaUrl = ReelsBackendApi.absoluteUrl(account, object.optString("mediaUrl", null));
        if (TextUtils.isEmpty(mediaUrl)) {
            FileLog.w("Ignoring Reel without public media " + id);
            return null;
        }

        JSONObject owner = object.optJSONObject("owner");
        final String username;
        final String displayName;
        final long userId;
        if (owner != null) {
            username = owner.optString("username", null);
            displayName = owner.optString("displayName", username);
            userId = owner.optLong("userId", 0);
        } else {
            username = object.optString("username", null);
            displayName = object.optString("displayName", username);
            userId = object.optLong("userId", 0);
        }
        if (TextUtils.isEmpty(username)) {
            FileLog.w("Ignoring profile Reel without an owner " + id);
            return null;
        }

        String thumbnailUrl = ReelsBackendApi.absoluteUrl(account, object.optString("thumbnailUrl", null));
        if (TextUtils.isEmpty(thumbnailUrl)) {
            thumbnailUrl = null;
        }
        String shareUrl = ReelsBackendApi.absoluteUrl(account, object.optString("shareUrl", null));
        if (TextUtils.isEmpty(shareUrl)) {
            shareUrl = ReelsBackendApi.absoluteUrl(account, "/r/" + Uri.encode(id));
        }

        final ReelsItem item = new ReelsItem(
                id,
                username,
                TextUtils.isEmpty(displayName) ? username : displayName,
                userId,
                mediaUrl,
                thumbnailUrl,
                shareUrl,
                object.optString("caption", ""),
                Math.max(0, object.optInt("duration", 0)),
                Math.max(0, object.optInt("width", 0)),
                Math.max(0, object.optInt("height", 0)),
                parseDate(object.optString("createdAt", null)),
                object.optBoolean("commentsEnabled", true)
        );
        item.views = Math.max(0, object.optLong("views", 0));
        item.likes = Math.max(0, object.optLong("likes", 0));
        item.comments = Math.max(0, object.optLong("comments", 0));
        item.saves = Math.max(0, object.optLong("saves", 0));
        item.shares = Math.max(0, object.optLong("shares", 0));
        item.liked = object.optBoolean("liked", false);
        item.saved = object.optBoolean("saved", false);
        return item;
    }

    public void copyCountersFrom(ReelsItem other) {
        if (other == null) return;
        views = other.views;
        likes = other.likes;
        comments = other.comments;
        saves = other.saves;
        shares = other.shares;
        liked = other.liked;
        saved = other.saved;
    }

    public String initial() {
        if (!TextUtils.isEmpty(ownerDisplayName)) {
            return ownerDisplayName.substring(0, 1).toUpperCase(Locale.US);
        }
        return "?";
    }

    private static long parseDate(String raw) {
        if (TextUtils.isEmpty(raw)) return 0;
        String normalised = raw.trim();
        String[] patterns = new String[]{
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                if (pattern.endsWith("'Z'")) format.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = format.parse(normalised);
                if (date != null) return date.getTime();
            } catch (Exception ignore) {
            }
        }
        return 0;
    }
}
