package org.telegram.ui.Components.Reels;

import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_reels;

/** One Reel comment or reply. A comment may contain text, a GIF document, or both. */
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
    /** The GIF document itself; playback/display goes through MTProto like any other file. */
    public final TLRPC.Document gif;

    public long likes;
    public boolean liked;
    public boolean pinned;
    public int replyCount;
    public boolean canDelete;
    public boolean canPin;
    public boolean canReply;

    private ReelsComment(long id, long userId, String username, String displayName, String text,
                         long parentId, String gifId, String gifUrl, int gifWidth, int gifHeight,
                         long createdAtMs, TLRPC.Document gif) {
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
        this.gif = gif;
    }

    public static ReelsComment fromTl(int account, TL_reels.ReelComment comment, TLRPC.User user) {
        if (comment == null || comment.id == 0) {
            return null;
        }
        final String username = user != null && !TextUtils.isEmpty(user.username) ? user.username : null;
        final String displayName = displayName(user, username);
        TLRPC.Document gif = null;
        if (comment.gif != null && comment.gif.id != 0) {
            gif = comment.gif;
        }
        final ReelsComment result = new ReelsComment(
                comment.id,
                comment.user_id,
                username,
                TextUtils.isEmpty(displayName) ? username : displayName,
                comment.text == null ? "" : comment.text,
                comment.reply_to,
                gif == null ? null : Long.toString(gif.id),
                null,
                gif == null ? 0 : gif.w,
                gif == null ? 0 : gif.h,
                comment.created_at > 0 ? comment.created_at * 1000L : 0,
                gif
        );
        result.pinned = comment.pinned;
        result.likes = Math.max(0, comment.likes);
        result.liked = comment.liked;
        result.replyCount = Math.max(0, comment.reply_count);
        result.canDelete = comment.can_delete;
        result.canPin = comment.can_pin;
        result.canReply = comment.can_reply;
        return result;
    }

    private static String displayName(TLRPC.User user, String fallback) {
        if (user == null) {
            return fallback;
        }
        final StringBuilder builder = new StringBuilder();
        if (!TextUtils.isEmpty(user.first_name)) {
            builder.append(user.first_name);
        }
        if (!TextUtils.isEmpty(user.last_name)) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(user.last_name);
        }
        return builder.length() == 0 ? fallback : builder.toString();
    }

    /** Poster of the GIF document, when the server attached one. */
    public TLRPC.PhotoSize gifThumb() {
        if (gif == null || gif.thumbs == null) {
            return null;
        }
        TLRPC.PhotoSize best = null;
        for (TLRPC.PhotoSize size : gif.thumbs) {
            if (size == null || size instanceof TLRPC.TL_photoSizeEmpty) {
                continue;
            }
            if (best == null || size.w > best.w) {
                best = size;
            }
        }
        return best;
    }

    public boolean isGifOnly() {
        return TextUtils.isEmpty(text) && gif != null;
    }

    public String displayNameOrUsername() {
        if (!TextUtils.isEmpty(displayName)) return displayName;
        if (!TextUtils.isEmpty(username)) return username;
        return "Reel";
    }
}
