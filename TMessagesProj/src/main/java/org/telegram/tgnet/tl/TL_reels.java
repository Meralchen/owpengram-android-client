package org.telegram.tgnet.tl;

import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLMethod;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.util.ArrayList;

/**
 * Reels: first-class short-video namespace.
 *
 * <p>Reels ride the same MTProto transport as everything else: the video is an
 * ordinary {@link TLRPC.Document} uploaded with upload.saveBigFilePart and
 * streamed with upload.getFile, so there is no REST client anywhere in this
 * package. Constructor ids are the deterministic SHA-256 prefixes defined in
 * docs/schema/reels.tl, not Telegram CRC32 values.
 */
public class TL_reels {

    private static <T extends TLObject> void writeVector(OutputSerializedData stream, ArrayList<T> list) {
        if (list == null) {
            list = new ArrayList<>();
        }
        Vector.serialize(stream, list);
    }

    // ---- reel#b826b69f -------------------------------------------------

    public static class Reel extends TLObject {
        public static final int constructor = 0xb826b69f;

        public long id;
        public long access_hash;
        public long owner_id;
        public TLRPC.Document media;
        public String caption = "";
        public ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        public boolean comments_enabled;
        public int duration;
        public int width;
        public int height;
        public int views;
        public int likes;
        public int comments;
        public int saves;
        public int shares;
        public boolean liked;
        public boolean saved;
        public boolean following;
        public int created_at;

        public static Reel TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (constructor != Reel.constructor) {
                if (exception) {
                    throw new RuntimeException("can't parse magic " + Integer.toHexString(constructor) + " in Reel");
                }
                return null;
            }
            Reel reel = new Reel();
            reel.readParams(stream, exception);
            return reel;
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            id = stream.readInt64(exception);
            access_hash = stream.readInt64(exception);
            owner_id = stream.readInt64(exception);
            media = TLRPC.Document.TLdeserialize(stream, stream.readInt32(exception), exception);
            caption = stream.readString(exception);
            entities = Vector.deserialize(stream, TLRPC.MessageEntity::TLdeserialize, exception);
            comments_enabled = stream.readBool(exception);
            duration = stream.readInt32(exception);
            width = stream.readInt32(exception);
            height = stream.readInt32(exception);
            views = stream.readInt32(exception);
            likes = stream.readInt32(exception);
            comments = stream.readInt32(exception);
            saves = stream.readInt32(exception);
            shares = stream.readInt32(exception);
            liked = stream.readBool(exception);
            saved = stream.readBool(exception);
            following = stream.readBool(exception);
            created_at = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
            stream.writeInt64(owner_id);
            media.serializeToStream(stream);
            stream.writeString(caption);
            writeVector(stream, entities);
            stream.writeBool(comments_enabled);
            stream.writeInt32(duration);
            stream.writeInt32(width);
            stream.writeInt32(height);
            stream.writeInt32(views);
            stream.writeInt32(likes);
            stream.writeInt32(comments);
            stream.writeInt32(saves);
            stream.writeInt32(shares);
            stream.writeBool(liked);
            stream.writeBool(saved);
            stream.writeBool(following);
            stream.writeInt32(created_at);
        }
    }

    // ---- reels.feed#64b19f7d / reels.feedNotModified#c49bc7b0 -----------

    public static class ReelsFeed extends TLObject {
        public static final int constructor = 0x64b19f7d;

        public int count;
        public ArrayList<Reel> reels = new ArrayList<>();
        public ArrayList<TLRPC.User> users = new ArrayList<>();
        public String next_offset = "";
        public long hash;

        public static ReelsFeed TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (constructor == TL_reels_feedNotModified.constructor) {
                return TL_reels_feedNotModified.TLdeserialize(stream, constructor, exception);
            }
            if (constructor != ReelsFeed.constructor) {
                if (exception) {
                    throw new RuntimeException("can't parse magic " + Integer.toHexString(constructor) + " in ReelsFeed");
                }
                return null;
            }
            ReelsFeed feed = new ReelsFeed();
            feed.readParams(stream, exception);
            return feed;
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            count = stream.readInt32(exception);
            reels = Vector.deserialize(stream, Reel::TLdeserialize, exception);
            users = Vector.deserialize(stream, TLRPC.User::TLdeserialize, exception);
            next_offset = stream.readString(exception);
            hash = stream.readInt64(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(count);
            writeVector(stream, reels);
            writeVector(stream, users);
            stream.writeString(next_offset);
            stream.writeInt64(hash);
        }
    }

    public static class TL_reels_feedNotModified extends ReelsFeed {
        public static final int constructor = 0xc49bc7b0;

        public static TL_reels_feedNotModified TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            TL_reels_feedNotModified feed = new TL_reels_feedNotModified();
            feed.readParams(stream, exception);
            return feed;
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            hash = stream.readInt64(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(hash);
        }
    }

    // ---- reelComment#2c4ab59f -----------------------------------------

    public static class ReelComment extends TLObject {
        public static final int constructor = 0x2c4ab59f;

        public int flags;
        public long id;
        public long reel_id;
        public long user_id;
        public long reply_to;
        public String text = "";
        public ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        public TLRPC.Document gif;
        public boolean pinned;
        public boolean can_delete;
        public boolean can_pin;
        public boolean can_reply;
        public int likes;
        public boolean liked;
        public int reply_count;
        public int created_at;

        public static ReelComment TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (constructor != ReelComment.constructor) {
                if (exception) {
                    throw new RuntimeException("can't parse magic " + Integer.toHexString(constructor) + " in ReelComment");
                }
                return null;
            }
            ReelComment comment = new ReelComment();
            comment.readParams(stream, exception);
            return comment;
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            id = stream.readInt64(exception);
            reel_id = stream.readInt64(exception);
            user_id = stream.readInt64(exception);
            reply_to = stream.readInt64(exception);
            text = stream.readString(exception);
            entities = Vector.deserialize(stream, TLRPC.MessageEntity::TLdeserialize, exception);
            if ((flags & 1) != 0) {
                gif = TLRPC.Document.TLdeserialize(stream, stream.readInt32(exception), exception);
            }
            pinned = stream.readBool(exception);
            can_delete = stream.readBool(exception);
            can_pin = stream.readBool(exception);
            can_reply = stream.readBool(exception);
            likes = stream.readInt32(exception);
            liked = stream.readBool(exception);
            reply_count = stream.readInt32(exception);
            created_at = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            if (gif != null) {
                flags |= 1;
            } else {
                flags &= ~1;
            }
            stream.writeInt32(flags);
            stream.writeInt64(id);
            stream.writeInt64(reel_id);
            stream.writeInt64(user_id);
            stream.writeInt64(reply_to);
            stream.writeString(text);
            writeVector(stream, entities);
            if ((flags & 1) != 0) {
                gif.serializeToStream(stream);
            }
            stream.writeBool(pinned);
            stream.writeBool(can_delete);
            stream.writeBool(can_pin);
            stream.writeBool(can_reply);
            stream.writeInt32(likes);
            stream.writeBool(liked);
            stream.writeInt32(reply_count);
            stream.writeInt32(created_at);
        }
    }

    // ---- reels.comments#2e497d9c --------------------------------------

    public static class ReelsComments extends TLObject {
        public static final int constructor = 0x2e497d9c;

        public int count;
        public ArrayList<ReelComment> comments = new ArrayList<>();
        public ArrayList<TLRPC.User> users = new ArrayList<>();
        public int next_offset;

        public static ReelsComments TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (constructor != ReelsComments.constructor) {
                if (exception) {
                    throw new RuntimeException("can't parse magic " + Integer.toHexString(constructor) + " in ReelsComments");
                }
                return null;
            }
            ReelsComments comments = new ReelsComments();
            comments.readParams(stream, exception);
            return comments;
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            count = stream.readInt32(exception);
            comments = Vector.deserialize(stream, ReelComment::TLdeserialize, exception);
            users = Vector.deserialize(stream, TLRPC.User::TLdeserialize, exception);
            next_offset = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(count);
            writeVector(stream, comments);
            writeVector(stream, users);
            stream.writeInt32(next_offset);
        }
    }

    // ---- reelActivity#886bff76 ----------------------------------------

    public static class ReelActivity extends TLObject {
        public static final int constructor = 0x886bff76;

        public static final int TYPE_LIKE = 0;
        public static final int TYPE_COMMENT = 1;
        public static final int TYPE_FOLLOW = 2;
        public static final int TYPE_SHARE = 3;
        public static final int TYPE_SAVE = 4;

        public long id;
        public int type;
        public long actor_id;
        public long reel_id;
        public long comment_id;
        public boolean read;
        public int created_at;

        public static ReelActivity TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (constructor != ReelActivity.constructor) {
                if (exception) {
                    throw new RuntimeException("can't parse magic " + Integer.toHexString(constructor) + " in ReelActivity");
                }
                return null;
            }
            ReelActivity activity = new ReelActivity();
            activity.readParams(stream, exception);
            return activity;
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            id = stream.readInt64(exception);
            type = stream.readInt32(exception);
            actor_id = stream.readInt64(exception);
            reel_id = stream.readInt64(exception);
            comment_id = stream.readInt64(exception);
            read = stream.readBool(exception);
            created_at = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt32(type);
            stream.writeInt64(actor_id);
            stream.writeInt64(reel_id);
            stream.writeInt64(comment_id);
            stream.writeBool(read);
            stream.writeInt32(created_at);
        }
    }

    // ---- reels.activity#1bf2172d --------------------------------------

    public static class ReelsActivity extends TLObject {
        public static final int constructor = 0x1bf2172d;

        public int count;
        public int unread;
        public ArrayList<ReelActivity> items = new ArrayList<>();
        public ArrayList<TLRPC.User> users = new ArrayList<>();
        public ArrayList<Reel> reels = new ArrayList<>();
        public String next_offset = "";

        public static ReelsActivity TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (constructor != ReelsActivity.constructor) {
                if (exception) {
                    throw new RuntimeException("can't parse magic " + Integer.toHexString(constructor) + " in ReelsActivity");
                }
                return null;
            }
            ReelsActivity activity = new ReelsActivity();
            activity.readParams(stream, exception);
            return activity;
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            count = stream.readInt32(exception);
            unread = stream.readInt32(exception);
            items = Vector.deserialize(stream, ReelActivity::TLdeserialize, exception);
            users = Vector.deserialize(stream, TLRPC.User::TLdeserialize, exception);
            reels = Vector.deserialize(stream, Reel::TLdeserialize, exception);
            next_offset = stream.readString(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(count);
            stream.writeInt32(unread);
            writeVector(stream, items);
            writeVector(stream, users);
            writeVector(stream, reels);
            stream.writeString(next_offset);
        }
    }

    // ---- reels.creator#3bbaeb40 ---------------------------------------

    public static class ReelsCreator extends TLObject {
        public static final int constructor = 0x3bbaeb40;

        public TLRPC.User user;
        public int reels;
        public int followers;
        public int following;
        public boolean is_following;
        public boolean is_self;

        public static ReelsCreator TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (constructor != ReelsCreator.constructor) {
                if (exception) {
                    throw new RuntimeException("can't parse magic " + Integer.toHexString(constructor) + " in ReelsCreator");
                }
                return null;
            }
            ReelsCreator creator = new ReelsCreator();
            creator.readParams(stream, exception);
            return creator;
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            user = TLRPC.User.TLdeserialize(stream, stream.readInt32(exception), exception);
            reels = stream.readInt32(exception);
            followers = stream.readInt32(exception);
            following = stream.readInt32(exception);
            is_following = stream.readBool(exception);
            is_self = stream.readBool(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            user.serializeToStream(stream);
            stream.writeInt32(reels);
            stream.writeInt32(followers);
            stream.writeInt32(following);
            stream.writeBool(is_following);
            stream.writeBool(is_self);
        }
    }

    // ---- updateReelActivity#238fd733 ----------------------------------

    public static class TL_updateReelActivity extends TLRPC.Update {
        public static final int constructor = 0x238fd733;

        public int type;
        public long actor_id;
        public long reel_id;
        public long comment_id;
        public int date;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            type = stream.readInt32(exception);
            actor_id = stream.readInt64(exception);
            reel_id = stream.readInt64(exception);
            comment_id = stream.readInt64(exception);
            date = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(type);
            stream.writeInt64(actor_id);
            stream.writeInt64(reel_id);
            stream.writeInt64(comment_id);
            stream.writeInt32(date);
        }
    }

    // ---- requests ------------------------------------------------------

    public static class getFeed extends TLMethod<ReelsFeed> {
        public static final int constructor = 0x1115db41;

        public int flags;
        public boolean for_you;
        public boolean following;
        public boolean latest;
        public String offset = "";
        public int limit;
        public long hash;

        @Override
        public ReelsFeed deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return ReelsFeed.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            if (for_you) flags |= 1;
            if (following) flags |= 2;
            if (latest) flags |= 4;
            stream.writeInt32(flags);
            stream.writeString(offset);
            stream.writeInt32(limit);
            stream.writeInt64(hash);
        }
    }

    public static class getReel extends TLMethod<Reel> {
        public static final int constructor = 0xb958e2e5;

        public long id;
        public long access_hash;

        @Override
        public Reel deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return Reel.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
        }
    }

    public static class create extends TLMethod<Reel> {
        public static final int constructor = 0xa8318178;

        public TLRPC.InputMedia media;
        public String caption = "";
        public ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        public int cover_time_ms;
        public boolean comments_enabled = true;

        @Override
        public Reel deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return Reel.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            media.serializeToStream(stream);
            stream.writeString(caption);
            writeVector(stream, entities);
            stream.writeInt32(cover_time_ms);
            stream.writeBool(comments_enabled);
        }
    }

    public static class edit extends TLMethod<Reel> {
        public static final int constructor = 0xfe19ab7c;

        public int flags;
        public long id;
        public long access_hash;
        public String caption;
        public boolean comments_enabled;

        public void setCaption(String value) {
            caption = value;
            flags |= 1;
        }

        public void setCommentsEnabled(boolean value) {
            comments_enabled = value;
            flags |= 2;
        }

        @Override
        public Reel deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return Reel.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
            if ((flags & 1) != 0) {
                stream.writeString(caption);
            }
            if ((flags & 2) != 0) {
                stream.writeBool(comments_enabled);
            }
        }
    }

    public static class delete extends TLMethod<TLRPC.Bool> {
        public static final int constructor = 0x67bad3c6;

        public long id;
        public long access_hash;

        @Override
        public TLRPC.Bool deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TLRPC.Bool.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
        }
    }

    public static class like extends TLMethod<Reel> {
        public static final int constructor = 0x21b8a9ec;

        public long id;
        public long access_hash;
        public boolean liked;

        @Override
        public Reel deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return Reel.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
            stream.writeBool(liked);
        }
    }

    public static class save extends TLMethod<Reel> {
        public static final int constructor = 0xf9436c64;

        public long id;
        public long access_hash;
        public boolean saved;

        @Override
        public Reel deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return Reel.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
            stream.writeBool(saved);
        }
    }

    public static class share extends TLMethod<Reel> {
        public static final int constructor = 0x7f7a21d2;

        public long id;
        public long access_hash;

        @Override
        public Reel deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return Reel.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
        }
    }

    public static class view extends TLMethod<TLRPC.Bool> {
        public static final int constructor = 0xebada416;

        public long id;
        public long access_hash;
        public int position_ms;
        public boolean completed;

        @Override
        public TLRPC.Bool deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TLRPC.Bool.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
            stream.writeInt32(position_ms);
            stream.writeBool(completed);
        }
    }

    public static class follow extends TLMethod<ReelsCreator> {
        public static final int constructor = 0x67d2cdc9;

        public TLRPC.InputUser user;
        public boolean following;

        @Override
        public ReelsCreator deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return ReelsCreator.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            user.serializeToStream(stream);
            stream.writeBool(following);
        }
    }

    public static class getCreator extends TLMethod<ReelsCreator> {
        public static final int constructor = 0x086cc09e;

        public String username = "";

        @Override
        public ReelsCreator deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return ReelsCreator.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(username);
        }
    }

    public static class getCreatorReels extends TLMethod<ReelsFeed> {
        public static final int constructor = 0x79e2f671;

        public String username = "";
        public String offset = "";
        public int limit;

        @Override
        public ReelsFeed deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return ReelsFeed.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(username);
            stream.writeString(offset);
            stream.writeInt32(limit);
        }
    }

    public static class getSaved extends TLMethod<ReelsFeed> {
        public static final int constructor = 0x952a8cfb;

        public String offset = "";
        public int limit;

        @Override
        public ReelsFeed deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return ReelsFeed.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(offset);
            stream.writeInt32(limit);
        }
    }

    public static class getComments extends TLMethod<ReelsComments> {
        public static final int constructor = 0xb4806f51;

        public long id;
        public long access_hash;
        public int offset;
        public int limit;

        @Override
        public ReelsComments deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return ReelsComments.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
            stream.writeInt32(offset);
            stream.writeInt32(limit);
        }
    }

    public static class getCommentReplies extends TLMethod<ReelsComments> {
        public static final int constructor = 0xe55503f2;

        public long id;
        public long access_hash;
        public long comment_id;
        public int offset;
        public int limit;

        @Override
        public ReelsComments deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return ReelsComments.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
            stream.writeInt64(comment_id);
            stream.writeInt32(offset);
            stream.writeInt32(limit);
        }
    }

    public static class sendComment extends TLMethod<ReelComment> {
        public static final int constructor = 0xa6324ed5;

        public int flags;
        public long id;
        public long access_hash;
        public long reply_to;
        public String text = "";
        public ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        public TLRPC.InputDocument gif;

        public void setGif(TLRPC.InputDocument value) {
            gif = value;
            if (value != null) {
                flags |= 1;
            } else {
                flags &= ~1;
            }
        }

        @Override
        public ReelComment deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return ReelComment.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
            stream.writeInt64(reply_to);
            stream.writeString(text);
            writeVector(stream, entities);
            if ((flags & 1) != 0) {
                gif.serializeToStream(stream);
            }
        }
    }

    public static class deleteComment extends TLMethod<TLRPC.Bool> {
        public static final int constructor = 0x393373d6;

        public long id;
        public long access_hash;
        public long comment_id;

        @Override
        public TLRPC.Bool deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TLRPC.Bool.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
            stream.writeInt64(comment_id);
        }
    }

    public static class likeComment extends TLMethod<ReelComment> {
        public static final int constructor = 0xc0cf7619;

        public long id;
        public long access_hash;
        public long comment_id;
        public boolean liked;

        @Override
        public ReelComment deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return ReelComment.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
            stream.writeInt64(comment_id);
            stream.writeBool(liked);
        }
    }

    public static class pinComment extends TLMethod<ReelComment> {
        public static final int constructor = 0xf1578654;

        public long id;
        public long access_hash;
        public long comment_id;
        public boolean pinned;

        @Override
        public ReelComment deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return ReelComment.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
            stream.writeInt64(comment_id);
            stream.writeBool(pinned);
        }
    }

    public static class getActivity extends TLMethod<ReelsActivity> {
        public static final int constructor = 0x56c647a8;

        public String offset = "";
        public int limit;

        @Override
        public ReelsActivity deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return ReelsActivity.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(offset);
            stream.writeInt32(limit);
        }
    }

    public static class readActivity extends TLMethod<TLRPC.Bool> {
        public static final int constructor = 0x6b7e28db;

        public int flags;
        public ArrayList<Long> ids = new ArrayList<>();
        public boolean all;

        public void setIDs(ArrayList<Long> value) {
            ids = value;
            flags |= 1;
        }

        public void setAll(boolean value) {
            all = value;
            if (value) {
                flags |= 2;
            } else {
                flags &= ~2;
            }
        }

        @Override
        public TLRPC.Bool deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TLRPC.Bool.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            if ((flags & 1) != 0) {
                stream.writeInt32(Vector.constructor);
                stream.writeInt32(ids.size());
                for (Long id : ids) {
                    stream.writeInt64(id);
                }
            }
        }
    }
}
