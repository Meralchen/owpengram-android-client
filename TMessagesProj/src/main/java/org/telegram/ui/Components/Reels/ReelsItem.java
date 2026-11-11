package org.telegram.ui.Components.Reels;

import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_reels;

import java.util.Locale;

/**
 * A permanent, profile-owned Reel. There is deliberately no channel or message
 * identity here, and no HTTP URL: media is a Telegram document streamed over
 * the account's MTProto connection.
 */
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

    /** Wire identity: reel id/access hash are needed by every reels.* call. */
    public final long tlId;
    public final long accessHash;
    /** The backing video document; null for pre-migration Reels. */
    public final TLRPC.Document media;
    public final TLRPC.PhotoSize thumb;

    public long views;
    public long likes;
    public long comments;
    public long saves;
    public long shares;
    public boolean liked;
    public boolean saved;
    public boolean following;
    public long resumePositionMs;

    private ReelsItem(String id, long tlId, long accessHash, String ownerUsername, String ownerDisplayName,
                      long ownerUserId, String mediaUrl, String thumbnailUrl, String shareUrl, String caption,
                      int durationSeconds, int width, int height, long createdAtMs, boolean commentsEnabled,
                      TLRPC.Document media, TLRPC.PhotoSize thumb) {
        this.id = id;
        this.tlId = tlId;
        this.accessHash = accessHash;
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
        this.media = media;
        this.thumb = thumb;
    }

    /** Builds a Reel from its MTProto projection plus the owner the feed shipped with it. */
    public static ReelsItem fromTl(int account, TL_reels.Reel reel, TLRPC.User owner) {
        if (reel == null || reel.id == 0) {
            return null;
        }
        final String id = Long.toString(reel.id);
        final String username = owner != null && !TextUtils.isEmpty(owner.username) ? owner.username : null;
        final String displayName = owner != null ? displayName(owner) : username;
        TLRPC.Document document = null;
        if (reel.media instanceof TLRPC.TL_document && reel.media.id != 0) {
            document = (TLRPC.Document) reel.media;
        }
        final TLRPC.PhotoSize thumb = document == null ? null : firstThumb(document);
        final ReelsItem item = new ReelsItem(
                id,
                reel.id,
                reel.access_hash,
                username,
                TextUtils.isEmpty(displayName) ? username : displayName,
                reel.owner_id,
                ReelsCache.streamUri(account, document),
                null,
                ReelsCache.shareUrl(account, id),
                reel.caption == null ? "" : reel.caption,
                Math.max(0, reel.duration),
                Math.max(0, reel.width),
                Math.max(0, reel.height),
                reel.created_at > 0 ? reel.created_at * 1000L : 0,
                reel.comments_enabled,
                document,
                thumb
        );
        item.views = Math.max(0, reel.views);
        item.likes = Math.max(0, reel.likes);
        item.comments = Math.max(0, reel.comments);
        item.saves = Math.max(0, reel.saves);
        item.shares = Math.max(0, reel.shares);
        item.liked = reel.liked;
        item.saved = reel.saved;
        item.following = reel.following;
        return item;
    }

    private static String displayName(TLRPC.User user) {
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
        return builder.length() == 0 ? user.username : builder.toString();
    }

    private static TLRPC.PhotoSize firstThumb(TLRPC.Document document) {
        if (document.thumbs == null || document.thumbs.isEmpty()) {
            return null;
        }
        TLRPC.PhotoSize best = null;
        for (TLRPC.PhotoSize size : document.thumbs) {
            if (size == null || size instanceof TLRPC.TL_photoSizeEmpty) {
                continue;
            }
            if (best == null || size.w > best.w) {
                best = size;
            }
        }
        return best;
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
        following = other.following;
    }

    public boolean hasPlayableMedia() {
        return !TextUtils.isEmpty(mediaUrl);
    }

    public String initial() {
        if (!TextUtils.isEmpty(ownerDisplayName)) {
            return ownerDisplayName.substring(0, 1).toUpperCase(Locale.US);
        }
        return "?";
    }
}
