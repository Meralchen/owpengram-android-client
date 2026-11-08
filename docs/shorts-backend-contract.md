# Backend work for profile-owned Reels

The Android client currently has the public profile listing contract
`GET /api/profiles/{username}/reels?limit=20&offset=0`. Direct profile uploads
cannot be implemented safely as public channel posts: a Reel belongs to a user
profile, while a channel message belongs to a channel. Keep those records and
permissions separate.

## Suggested Go/MTProto design

Add a `reels` domain/store in the Go server with at least:

- `id`, `owner_user_id`, `storage_key`, `mime_type`, `size_bytes`,
  `duration_seconds`, `width`, `height`, `caption`, `created_at`, `deleted_at`;
- an upload state (`pending`, `ready`, `failed`) and an idempotency key;
- optional source `channel_id` and `message_id` for channel-post Reels. These
  fields stay null for a profile-owned upload.

Preserve channel Reels as normal channel messages. Add an authenticated,
single-use upload-session contract for profile-owned Reels. One REST shape that
fits the profile API is:

```text
POST   /api/profiles/me/reels/uploads
PUT    /api/profiles/me/reels/uploads/{uploadId}/parts/{partNumber}
POST   /api/profiles/me/reels/uploads/{uploadId}/complete
DELETE /api/profiles/me/reels/uploads/{uploadId}
GET    /api/reels/{reelId}/media       (Range supported)
GET    /api/reels/feed?limit=20&cursor=...
```

The `me` routes must derive the owner from authentication. They must not accept
an owner ID, channel username or storage key as proof of ownership. Since the
mobile app authenticates with MTProto, add a short-lived, one-use upload ticket
minted from that authenticated session (for example through an
`owpengram.reels.getUploadTicket` method in the existing generated TL schema).
This is a Reels method, not a `shorts.*` schema, and it must not carry an admin
credential. The ticket is scoped to the logged-in owner, one upload and a short
expiry.

The upload request should include a client idempotency key and validated
metadata. The server should derive duration and dimensions from the video and
reject claimed values that do not match. `complete` publishes the row only
after all parts are present and validated.

The feed/profile response must distinguish profile-owned media from channel
messages:

```json
{
  "items": [{
    "id": "opaque-reel-id",
    "source": "profile",
    "owner": {"username": "alice", "displayName": "Alice"},
    "caption": "",
    "mediaUrl": "/api/reels/opaque-reel-id/media",
    "thumbnailUrl": null,
    "duration": 12,
    "views": 93,
    "likes": 8,
    "comments": 2,
    "liked": false,
    "createdAt": "2026-11-08T12:00:00Z"
  }],
  "nextOffset": null
}
```

For channel-backed items, `source` can be `channel` and the item can carry its
channel username/message ID. For profile-owned items, never fabricate those
fields. Add authenticated reaction/comment operations for direct profile items
or a generated `reels.*` RPC contract; continue using `messages.sendReaction`
and discussion RPCs for channel messages.

1. The logged-in MTProto identity requests a short-lived upload session. The
   server derives `owner_user_id` from that authenticated session, never from a
   client-supplied user ID. A generated `owpengram.reels.*` request mints the
   scoped ticket above; do not add a `shorts.*` TL namespace or use an admin
   token.
2. The client uses the existing video picker and file pipeline. The server
   accepts resumable parts, verifies ownership of the upload session, and
   commits metadata only after all parts pass size, MIME, duration and media
   validation.
3. The commit operation is idempotent. It creates a profile-owned Reel row and
   returns its stable Reel ID. Failed or abandoned sessions expire and are
   garbage-collected.
4. `GET /api/profiles/{username}/reels?limit=20&offset=0` returns only ready,
   non-deleted Reels for that creator. Return `nextOffset: null` at the end,
   `items: []` for a successful empty result, and a separate error status for
   failures. Never expose private-channel media in this public response.
5. Provide an authenticated original-file endpoint with byte-range support for
   direct profile-owned Reels. Channel-message Reels continue to play through
   `upload.getFile`. Do not return an HLS URL until HLS storage and workers are
   deployed.

For a unified public feed, add a paginated `GET /api/reels/feed` that merges
ready profile-owned Reels with validated public channel-video records. Its items
must identify the source explicitly (`profile` or `channel`) and include the
creator, caption, duration, counts and stable cursor. Keep likes and comments
authorized against the Reel owner/source; do not pretend a profile-owned Reel
has a channel peer or channel message ID. Channel-post reactions and discussion
continue to use `messages.sendReaction` and the normal discussion RPCs.

## Android integration needed after the endpoint is available

The `+ Reel` entry should open the native video picker, validate the selected
video, request an upload session, upload resumable parts, and show progress,
cancel and retry. After commit, insert the returned item into the profile grid
and feed. It must not open a channel chooser or call `stories.sendStory`.

The current Android profile grid uses the public listing endpoint and resolves
channel-backed items with MTProto. It needs the authenticated upload-session
request and direct-file source before it can publish and play profile-owned
uploads end to end.

## Rollout checks

- A user can upload only to their own profile; a forged owner ID is ignored.
- Other users can list only ready public Reels and cannot read private bytes.
- Duplicate commit requests return the same Reel ID.
- Deleted and failed uploads disappear from profile and feed listings.
- Original-file range playback works without HLS.
- Channel video posts, channel Reels, Stories and existing story upload remain
  unchanged.
