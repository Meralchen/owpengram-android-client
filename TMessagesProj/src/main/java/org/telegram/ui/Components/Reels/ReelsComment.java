package org.telegram.ui.Components.Reels;

import android.text.TextUtils;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** One REST comment or reply. A comment may contain text, a GIF, or both. */
public final class ReelsComment {

    public final long id;
    public final long userId;
    public final String username;
    public final String displayName;
    public final String text;
    public final long parentId;
    public final String gifId;
    public final String gifUrl;
    public final int gifWidth;
    public final int gifHeight;
    public final long createdAtMs;

    public long likes;
    public boolean liked;
    public boolean pinned;
    public int replyCount;
    public boolean canDelete;
    public boolean canPin;
    public boolean canReply;

    private ReelsComment(long id, long userId, String username, String displayName, String text,
                         long parentId, String gifId, String gifUrl, int gifWidth, int gifHeight,
                         long createdAtMs) {
        this.id = id;
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.text = text;
        this.parentId = parentId;
        this.gifId = gifId;
        this.gifUrl = gifUrl;
        this.gifWidth = gifWidth;
        this.gifHeight = gifHeight;
        this.createdAtMs = createdAtMs;
    }

    public static ReelsComment fromJson(int account, JSONObject object) {
        if (object == null) {
            return null;
        }
        final long id = object.optLong("id", 0);
        if (id <= 0) {
            return null;
        }
        final String username = object.optString("username", null);
        final String displayName = object.optString("displayName", username);
        String gifId = null;
        String gifUrl = null;
        int gifWidth = 0;
        int gifHeight = 0;
        JSONObject gif = object.optJSONObject("gif");
        if (gif != null) {
            gifId = gif.optString("id", null);
            gifUrl = ReelsBackendApi.absoluteUrl(account, gif.optString("url", null));
            gifWidth = gif.optInt("width", 0);
            gifHeight = gif.optInt("height", 0);
        }
        final ReelsComment comment = new ReelsComment(
                id,
                object.optLong("userId", 0),
                TextUtils.isEmpty(username) ? null : username,
                TextUtils.isEmpty(displayName) ? username : displayName,
                object.optString("text", ""),
                object.optLong("parentId", 0),
                gifId,
                gifUrl,
                gifWidth,
                gifHeight,
                parseDate(object.optString("createdAt", null))
        );
        comment.pinned = object.optBoolean("pinned", false);
        comment.likes = Math.max(0, object.optLong("likes", 0));
        comment.liked = object.optBoolean("liked", false);
        comment.replyCount = Math.max(0, object.optInt("replyCount", 0));
        comment.canDelete = object.optBoolean("canDelete", false);
        comment.canPin = object.optBoolean("canPin", false);
        comment.canReply = object.optBoolean("canReply", false);
        return comment;
    }

    public boolean isGifOnly() {
        return TextUtils.isEmpty(text) && !TextUtils.isEmpty(gifUrl);
    }

    public String displayNameOrUsername() {
        if (!TextUtils.isEmpty(displayName)) return displayName;
        if (!TextUtils.isEmpty(username)) return username;
        return "Reel";
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
