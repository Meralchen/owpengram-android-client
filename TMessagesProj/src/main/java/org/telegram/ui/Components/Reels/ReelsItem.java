package org.telegram.ui.Components.Reels;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.VideoPlayer;

/**
 * A Reel is either a channel message or a profile-owned REST record. Channel items arrive from
 * the feed unresolved and are filled in lazily around the visible page; profile items use their
 * opaque server Reel ID.
 */
public final class ReelsItem {

    public enum Source { CHANNEL, PROFILE }

    public static final String HEART = "\u2764";

    public final int account;
    public final Source source;

    /** Stable server item id: the profile Reel id, or {@code channel:<id>:<messageId>}. */
    public String id;

    public long channelId;
    public int messageId;
    public MessageObject messageObject;
    public TLRPC.Document document;

    public String reelId;
    public String mediaUrl;
    public String thumbnailUrl;
    public String creatorName;
    public String creatorUsername;
    public long ownerUserId;
    /** Canonical public-profile API share URL, when this item came from a profile grid. */
    public String publicShareUrl;

    public String channelUsername;
    public String channelTitle;
    private String channelCaption;
    private double channelDuration;
    private int channelWidth;
    private int channelHeight;

    /** Channel item has no document yet; playback/comments wait for lazy resolution. */
    public boolean resolved;

    private String profileCaption;
    private double profileDuration;
    private int profileWidth;
    private int profileHeight;

    public boolean liked;
    public int likes;
    public int views;
    public int comments;
    public int saves;
    public int shares;
    public boolean saved;
    public boolean commentsEnabled;

    /** Position the user last watched, restored when the page comes back. */
    public long resumePositionMs;

    ReelsItem(int account, TLRPC.Message message, TLRPC.Document document) {
        this.account = account;
        this.source = Source.CHANNEL;
        this.channelId = message.peer_id.channel_id;
        this.messageId = message.id;
        this.id = stableId(channelId, messageId);
        this.messageObject = new MessageObject(account, message, false, false);
        this.document = document;
        this.resolved = document != null;
        applyMessage(message);
    }

    private ReelsItem(int account, String reelId) {
        this.account = account;
        this.source = Source.PROFILE;
        this.reelId = reelId;
        this.id = reelId;
        this.commentsEnabled = true;
    }

    private ReelsItem(int account, String id, long channelId, int messageId) {
        this.account = account;
        this.source = Source.CHANNEL;
        this.id = id;
        this.channelId = channelId;
        this.messageId = messageId;
        this.resolved = false;
        this.commentsEnabled = true;
    }

    public static ReelsItem from(int account, TLRPC.Message message) {
        if (!(message instanceof TLRPC.TL_message) || message.peer_id == null || message.peer_id.channel_id == 0) {
            return null;
        }
        final TLRPC.Document document = playableVideo(message);
        if (document == null) {
            return null;
        }
        return new ReelsItem(account, message, document);
    }

    /** Parses a profile-owned Reel without inventing a channel peer or MTProto message ID. */
    public static ReelsItem fromProfile(int account, JSONObject object) {
        if (object == null) return null;
        final String id = object.optString("id", "");
        final String media = object.optString("mediaUrl", "");
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(media)) return null;
        final ReelsItem item = new ReelsItem(account, id);
        item.mediaUrl = ReelsBackendApi.absoluteUrl(account, media);
        item.thumbnailUrl = ReelsBackendApi.absoluteUrl(account, object.optString("thumbnailUrl", null));
        item.profileCaption = object.optString("caption", "");
        item.profileDuration = object.optDouble("duration", 0);
        item.profileWidth = object.optInt("width", 0);
        item.profileHeight = object.optInt("height", 0);
        item.likes = object.optInt("likes", object.optInt("likeCount", 0));
        item.comments = object.optInt("comments", object.optInt("commentCount", 0));
        item.views = object.optInt("views", object.optInt("viewCount", 0));
        item.saves = object.optInt("saves", object.optInt("saveCount", 0));
        item.shares = object.optInt("shares", object.optInt("shareCount", 0));
        item.liked = object.optBoolean("liked", false);
        item.saved = object.optBoolean("saved", false);
        item.commentsEnabled = object.optBoolean("commentsEnabled", true);
        final JSONObject owner = ownerObject(object);
        if (owner != null) {
            item.creatorName = owner.optString("displayName", owner.optString("name", owner.optString("title", "")));
            item.creatorUsername = owner.optString("username", "");
            item.ownerUserId = owner.optLong("userId", owner.optLong("id", 0));
        }
        if (TextUtils.isEmpty(item.creatorName)) item.creatorName = object.optString("creatorName", "");
        if (TextUtils.isEmpty(item.creatorUsername)) item.creatorUsername = object.optString("creatorUsername", "");
        if (item.ownerUserId == 0) item.ownerUserId = object.optLong("ownerUserId", 0);
        item.publicShareUrl = object.optString("shareUrl", "");
        return item.mediaUrl != null ? item : null;
    }

    /**
     * Parses a channel item from the feed without blocking on contacts.resolveUsername /
     * channels.getMessages. The viewer resolves the visible items through {@link #applyResolved}.
     */
    public static ReelsItem fromChannelFeed(int account, JSONObject object) {
        if (object == null) return null;
        final JSONObject channel = object.optJSONObject("channel");
        final String serverId = object.optString("id", "");
        final int messageId = object.optInt("messageId", object.optInt("message_id", parseMessageId(serverId)));
        final long channelId = object.optLong("channelId", object.optLong("channel_id", parseChannelId(serverId)));
        final String id = TextUtils.isEmpty(serverId) ? stableId(channelId, messageId) : serverId;
        if (TextUtils.isEmpty(id) || messageId <= 0) return null;

        final ReelsItem item = new ReelsItem(account, id, channelId, messageId);
        item.channelUsername = object.optString("channelUsername",
                channel != null ? channel.optString("username", "") : "");
        item.channelTitle = object.optString("channelTitle",
                channel != null ? channel.optString("title", "") : "");
        item.channelCaption = object.optString("caption", "");
        item.channelDuration = object.optDouble("duration", 0);
        item.channelWidth = object.optInt("width", 0);
        item.channelHeight = object.optInt("height", 0);
        item.views = object.optInt("views", object.optInt("viewCount", 0));
        item.likes = object.optInt("likes", object.optInt("likeCount", 0));
        item.comments = object.optInt("comments", 0);
        item.likes = object.optInt("likes", item.likes);
        item.saves = object.optInt("saves", 0);
        item.shares = object.optInt("shares", 0);
        item.liked = object.optBoolean("liked", false);
        item.saved = false;
        item.commentsEnabled = true;
        final JSONObject owner = ownerObject(object);
        if (owner != null) {
            item.creatorName = owner.optString("displayName", owner.optString("name", owner.optString("title", "")));
            item.creatorUsername = owner.optString("username", "");
            item.ownerUserId = owner.optLong("userId", owner.optLong("id", 0));
        }
        if (TextUtils.isEmpty(item.creatorName)) item.creatorName = item.channelTitle;
        if (TextUtils.isEmpty(item.creatorUsername)) item.creatorUsername = item.channelUsername;
        item.publicShareUrl = object.optString("shareUrl", "");
        return item;
    }

    private static JSONObject ownerObject(JSONObject object) {
        JSONObject owner = object.optJSONObject("owner");
        if (owner == null) owner = object.optJSONObject("creator");
        if (owner == null) owner = object.optJSONObject("profile");
        return owner;
    }

    /** Copies the resolved MTProto video into this placeholder, preserving server counters. */
    public void applyResolved(ReelsItem resolvedItem) {
        if (resolvedItem == null || resolvedItem.source != Source.CHANNEL) return;
        channelId = resolvedItem.channelId;
        messageId = resolvedItem.messageId;
        messageObject = resolvedItem.messageObject;
        document = resolvedItem.document;
        resolved = document != null;
        if (messageObject != null) {
            applyMessage(messageObject.messageOwner);
        }
        if (TextUtils.isEmpty(channelUsername)) channelUsername = resolvedItem.channelUsername;
        if (TextUtils.isEmpty(channelTitle)) channelTitle = resolvedItem.channelTitle;
    }

    /** A regular video document: not a round video, voice note or animated GIF. */
    public static TLRPC.Document playableVideo(TLRPC.Message message) {
        if (message == null || !(message.media instanceof TLRPC.TL_messageMediaDocument)) {
            return null;
        }
        final TLRPC.Document document = message.media.document;
        if (document == null || document instanceof TLRPC.TL_documentEmpty) {
            return null;
        }
        boolean video = false;
        for (int i = 0; i < document.attributes.size(); i++) {
            final TLRPC.DocumentAttribute attribute = document.attributes.get(i);
            if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                if (attribute.round_message) {
                    return null;
                }
                video = true;
            } else if (attribute instanceof TLRPC.TL_documentAttributeAnimated
                    || attribute instanceof TLRPC.TL_documentAttributeAudio) {
                return null;
            }
        }
        if (!video || (document.mime_type != null && !document.mime_type.startsWith("video/"))) {
            return null;
        }
        return document;
    }

    public long getDialogId() {
        return source == Source.CHANNEL ? -channelId : 0;
    }

    public TLRPC.Message getMessage() {
        return messageObject != null ? messageObject.messageOwner : null;
    }

    public String stableId() {
        if (!TextUtils.isEmpty(id)) return id;
        return source == Source.CHANNEL ? stableId(channelId, messageId) : reelId;
    }

    private static String stableId(long channelId, int messageId) {
        return "channel:" + channelId + ":" + messageId;
    }

    private static long parseChannelId(String id) {
        if (id == null || !id.startsWith("channel:")) return 0;
        final int end = id.indexOf(':', 8);
        try {
            return Long.parseLong(end < 0 ? id.substring(8) : id.substring(8, end));
        } catch (Exception ignore) {
            return 0;
        }
    }

    private static int parseMessageId(String id) {
        if (id == null || !id.startsWith("channel:")) return 0;
        final int second = id.indexOf(':', 8);
        if (second < 0 || second + 1 >= id.length()) return 0;
        try {
            return Integer.parseInt(id.substring(second + 1));
        } catch (Exception ignore) {
            return 0;
        }
    }

    public void applyMessage(TLRPC.Message message) {
        if (message == null) return;
        views = message.views;
        comments = message.replies != null ? message.replies.replies : 0;
        commentsEnabled = message.replies != null && message.replies.comments;
        applyReactions(message.reactions);
    }

    public void applyReactions(TLRPC.TL_messageReactions reactions) {
        if (getMessage() != null) getMessage().reactions = reactions;
        liked = false;
        likes = 0;
        if (reactions == null) {
            return;
        }
        for (int i = 0; i < reactions.results.size(); i++) {
            final TLRPC.ReactionCount count = reactions.results.get(i);
            if (isHeart(count.reaction)) {
                likes = count.count;
                liked = count.chosen;
            }
        }
    }

    public static boolean isHeart(TLRPC.Reaction reaction) {
        if (!(reaction instanceof TLRPC.TL_reactionEmoji)) {
            return false;
        }
        final String emoticon = ((TLRPC.TL_reactionEmoji) reaction).emoticon;
        return emoticon != null && emoticon.replace("\uFE0F", "").equals(HEART);
    }

    public double getDuration() {
        if (source == Source.CHANNEL) {
            return document != null ? MessageObject.getDocumentDuration(document) : channelDuration;
        }
        return profileDuration;
    }

    public int getVideoWidth() {
        if (source == Source.CHANNEL) {
            return document != null ? MessageObject.getVideoWidth(document) : channelWidth;
        }
        return profileWidth;
    }

    public int getVideoHeight() {
        if (source == Source.CHANNEL) {
            return document != null ? MessageObject.getVideoHeight(document) : channelHeight;
        }
        return profileHeight;
    }

    public String getCaption() {
        if (source == Source.PROFILE) return TextUtils.isEmpty(profileCaption) ? null : profileCaption;
        if (messageObject != null) {
            final String text = messageObject.messageOwner.message;
            return TextUtils.isEmpty(text) ? null : text;
        }
        return TextUtils.isEmpty(channelCaption) ? null : channelCaption;
    }

    /**
     * tg:// URI resolved by {@link org.telegram.messenger.FileStreamLoadOperation}
     * through upload.getFile; the registered parent lets FileRefController refresh
     * an expired file reference.
     */
    public Uri mtprotoUri() {
        if (source != Source.CHANNEL || document == null || messageObject == null) return null;
        try {
            final int reference = FileLoader.getInstance(account).getFileReference(messageObject);
            return VideoPlayer.VideoUri.getUri(account, document, reference);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public String cacheKey() {
        if (source == Source.PROFILE) {
            return ReelsCache.profileKey(account, reelId);
        }
        if (channelId > 0 && messageId > 0) {
            return ReelsCache.channelKey(account, channelId, messageId);
        }
        return ReelsCache.keyPrefix(account) + "pending:" + stableId();
    }
}
