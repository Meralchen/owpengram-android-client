package org.telegram.ui.Components.Shorts;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.VideoPlayer;

/**
 * One clip: a regular channel video message. Identity is {channelId, messageId};
 * the document is only the media, never an id.
 */
public final class ShortsItem {

    public static final String HEART = "\u2764";

    public final int account;
    public final long channelId;
    public final int messageId;
    public final MessageObject messageObject;
    public final TLRPC.Document document;

    /** next_rate of the searchGlobal page this clip came from (feed cursor). */
    public int pageRate;

    public boolean liked;
    public int likes;
    public int views;
    public int comments;
    public boolean commentsEnabled;

    /** Position the user last watched, restored when the page comes back. */
    public long resumePositionMs;

    ShortsItem(int account, TLRPC.Message message, TLRPC.Document document) {
        this.account = account;
        this.channelId = message.peer_id.channel_id;
        this.messageId = message.id;
        this.messageObject = new MessageObject(account, message, false, false);
        this.document = document;
        applyMessage(message);
    }

    public static ShortsItem from(int account, TLRPC.Message message) {
        if (!(message instanceof TLRPC.TL_message) || message.peer_id == null || message.peer_id.channel_id == 0) {
            return null;
        }
        final TLRPC.Document document = playableVideo(message);
        if (document == null) {
            return null;
        }
        return new ShortsItem(account, message, document);
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
        return -channelId;
    }

    public TLRPC.Message getMessage() {
        return messageObject.messageOwner;
    }

    public void applyMessage(TLRPC.Message message) {
        views = message.views;
        comments = message.replies != null ? message.replies.replies : 0;
        commentsEnabled = message.replies != null && message.replies.comments;
        applyReactions(message.reactions);
    }

    public void applyReactions(TLRPC.TL_messageReactions reactions) {
        getMessage().reactions = reactions;
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
        return MessageObject.getDocumentDuration(document);
    }

    public int getVideoWidth() {
        return MessageObject.getVideoWidth(document);
    }

    public int getVideoHeight() {
        return MessageObject.getVideoHeight(document);
    }

    public String getCaption() {
        final String text = getMessage().message;
        return TextUtils.isEmpty(text) ? null : text;
    }

    /**
     * tg:// URI resolved by {@link org.telegram.messenger.FileStreamLoadOperation}
     * through upload.getFile; the registered parent lets FileRefController refresh
     * an expired file reference.
     */
    public Uri mtprotoUri() {
        try {
            final int reference = FileLoader.getInstance(account).getFileReference(messageObject);
            return VideoPlayer.VideoUri.getUri(account, document, reference);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public String cacheKey() {
        return ShortsCache.keyPrefix(account) + document.id;
    }
}
