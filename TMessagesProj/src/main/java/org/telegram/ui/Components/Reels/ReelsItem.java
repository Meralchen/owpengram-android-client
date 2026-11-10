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
 * A Reel is either a channel message or a profile-owned REST record. Only channel items
 * have a channel/message identity; profile items use their opaque Reel ID.
 */
public final class ReelsItem {

    public enum Source { CHANNEL, PROFILE }

    public static final String HEART = "\u2764";

    public final int account;
    public final Source source;
    public final long channelId;
    public final int messageId;
    public final MessageObject messageObject;
    public final TLRPC.Document document;
    public String reelId;
    public String mediaUrl;
    public String thumbnailUrl;
    public String creatorName;
    public String creatorUsername;
    public String playbackAuthorizationHeader;
    private String profileCaption;
    private double profileDuration;
    private int profileWidth;
    private int profileHeight;
    /** Canonical public-profile API share URL, when this item came from a profile grid. */
    public String publicShareUrl;

    /** next_rate or cursor marker. */
    public int pageRate;

    public boolean liked;
    public int likes;
    public int views;
    public int comments;
    public boolean commentsEnabled;

    /** Position the user last watched, restored when the page comes back. */
    public long resumePositionMs;

    ReelsItem(int account, TLRPC.Message message, TLRPC.Document document) {
        this.account = account;
        this.source = Source.CHANNEL;
        this.channelId = message.peer_id.channel_id;
        this.messageId = message.id;
        this.messageObject = new MessageObject(account, message, false, false);
        this.document = document;
        applyMessage(message);
    }

    private ReelsItem(int account, String reelId) {
        this.account = account;
        this.source = Source.PROFILE;
        this.channelId = 0;
        this.messageId = 0;
        this.messageObject = null;
        this.document = null;
        this.reelId = reelId;
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
        String id = object.optString("id", "");
        String media = object.optString("mediaUrl", "");
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(media)) return null;
        ReelsItem item = new ReelsItem(account, id);
        item.mediaUrl = ReelsBackendApi.absoluteUrl(account, media);
        item.thumbnailUrl = ReelsBackendApi.absoluteUrl(account, object.optString("thumbnailUrl", null));
        item.profileCaption = object.optString("caption", "");
        item.profileDuration = object.optDouble("duration", 0);
        item.profileWidth = object.optInt("width", 0);
        item.profileHeight = object.optInt("height", 0);
        item.likes = object.optInt("likes", object.optInt("likeCount", 0));
        item.comments = object.optInt("comments", object.optInt("commentCount", 0));
        item.views = object.optInt("views", object.optInt("viewCount", 0));
        item.liked = object.optBoolean("liked", false);
        JSONObject owner = object.optJSONObject("owner");
        if (owner == null) owner = object.optJSONObject("creator");
        if (owner == null) owner = object.optJSONObject("profile");
        if (owner != null) {
            item.creatorName = owner.optString("displayName", owner.optString("name", owner.optString("title", "")));
            item.creatorUsername = owner.optString("username", "");
        }
        if (TextUtils.isEmpty(item.creatorName)) item.creatorName = object.optString("creatorName", "");
        if (TextUtils.isEmpty(item.creatorUsername)) item.creatorUsername = object.optString("creatorUsername", "");
        item.publicShareUrl = object.optString("shareUrl", "");
        return item.mediaUrl != null ? item : null;
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
        return source == Source.PROFILE ? profileDuration : MessageObject.getDocumentDuration(document);
    }

    public int getVideoWidth() {
        return source == Source.PROFILE ? profileWidth : MessageObject.getVideoWidth(document);
    }

    public int getVideoHeight() {
        return source == Source.PROFILE ? profileHeight : MessageObject.getVideoHeight(document);
    }

    public String getCaption() {
        if (source == Source.PROFILE) return TextUtils.isEmpty(profileCaption) ? null : profileCaption;
        final String text = getMessage().message;
        return TextUtils.isEmpty(text) ? null : text;
    }

    /**
     * tg:// URI resolved by {@link org.telegram.messenger.FileStreamLoadOperation}
     * through upload.getFile; the registered parent lets FileRefController refresh
     * an expired file reference.
     */
    public Uri mtprotoUri() {
        if (source != Source.CHANNEL) return null;
        try {
            final int reference = FileLoader.getInstance(account).getFileReference(messageObject);
            return VideoPlayer.VideoUri.getUri(account, document, reference);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public String cacheKey() {
        return source == Source.PROFILE ? ReelsCache.profileKey(account, reelId) : ReelsCache.keyPrefix(account) + document.id;
    }
}
