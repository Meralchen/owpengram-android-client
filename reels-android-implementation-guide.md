# Reels v2 — Android client guide

Status: authoritative for the client. Supersedes the previous horizontal,
story-like Reels guide. The backend contract is the Reels v2 handoff dated
2026-11-10.

## Non-negotiable decisions

- **Reels are vertical.** Swipe up for the next Reel, swipe down for the previous.
  Horizontal drags never page.
- **Comments live in a bottom sheet**, opened by the comment button. A full-screen
  swipe-up must not open comments; that fights vertical paging.
- **Profile Reels are REST and public.** `media`, `thumbnail` and `/r/{id}` need no
  authorization. Channel items stay on MTProto (`upload.getFile`).
- **Old Reels may have no cover.** `thumbnailUrl` may be null; show a gradient/initial
  placeholder, never a crash or a blank grid cell.
- **GIF comments are uploaded `image/gif` files**, not Telegram GIF documents.
- **Reels follows are separate from Telegram contacts and channel membership.**
- **No `stories.sendStory`.** Reels are permanent records.

## Visual language

Reels matches Telegram. The full token contract is in
[`docs/custom-made/reels-design-system.md`](docs/custom-made/reels-design-system.md).
The short version:

- All colours, shapes and shared dp values come from
  `TMessagesProj/src/main/java/org/telegram/ui/Components/Reels/ReelsTheme.java`.
- The viewer stage is always dark, like Telegram's story/photo viewer.
- The profile grid, comment sheets and upload chrome use the active
  `Theme.ResourcesProvider`; pass it down from the host screen.
- Every themed Reels surface observes `NotificationCenter.didSetNewTheme` and updates in
  place. Change a token once, and the viewer, grid, sheets and upload chrome all follow.

## Layout

```
ReelsViewer (tab 0, BaseFragment)
├── top chrome: back, "Reels", mute, more
├── ReelsViewPager (vertical RecyclerView + PagerSnapHelper)
│   └── ReelsPageView
│       ├── full-bleed center-cropped video
│       ├── top/bottom legibility gradients
│       ├── right rail: like, comment, share, views
│       ├── creator row + caption (bottom left)
│       └── thin bottom progress with elapsed/total time and drag-to-seek
├── ReelsPlayerPool (current ± 1, max 3, public profile media, mute)
└── ReelsCommentsSheet / ReelsProfileCommentsSheet
```

Profile Reels tab: three columns, 9:16 cells, 1 dp gaps, 6 dp radius, cover or
initial-gradient placeholder, play badge and view count. The tab constant is unique
(`SharedMediaLayout.TAB_REELS = 16`; `TAB_POLL` stays 15).

## Gestures

| Gesture | Action |
|---|---|
| Vertical swipe | Next / previous Reel |
| Single tap | Play / pause |
| Double tap | Like + heart at the tap point |
| Long press | Clean mode (hold) |
| Tap comment | Open the sheet |
| Drag bottom progress | Seek inside the clip |

## API quick reference

Profile items (`source: "profile"`) use:

- `GET /api/reels/feed?mode=for_you|latest|following&limit=&cursor=`
- `GET /api/reels/{id}`, `GET /api/reels/{id}/media`, `GET /api/reels/{id}/thumbnail`
- `GET /api/profiles/{username}/reels?limit=&offset=`
- `PUT/DELETE /api/reels/{id}/like`, `/save`, `/share`, `/view`
- `GET/POST /api/reels/{id}/comments`, replies, comment likes, pin, delete
- `POST /api/comment-gifs`
- `GET/PUT/DELETE /api/creators/{username}/follow`
- `POST /api/profiles/me/reels/uploads` and part/complete/cancel calls

Channel items (`source: "channel"`) resolve lazily through MTProto and keep Telegram
reactions/discussion. Never fabricate a channel peer or message ID for a profile Reel.

Authorization: `Authorization: Telegram <userId>:<base64url(exported bytes)>` for writes
and personalized reads, cached at most four minutes with one refresh/retry on 401.
Upload parts use the session's Bearer ticket. Public reads send no authorization.

## Implemented in the current client pass

- `ReelsTheme` design-token layer with `Theme.ResourcesProvider` support.
- Vertical `ReelsViewPager`; the old axis arbitration and swipe-to-comments/dismiss are
  gone.
- `ReelsPageView` rebuilt: center-cropped video, bottom progress with elapsed/total and
  seek, right action rail, creator/caption block, play/loading/error states, 48 dp
  targets, reduced-motion support.
- Viewer chrome: back, title, mute (with pool volume), more, theme refresh.
- Profile grid: 9:16 cells, cover/initial placeholder, play badge, view count, themed
  `+ Reel` pill, profile provider inherited from `SharedMediaLayout`.
- Comment sheets: Telegram drag handle, header with count and close, themed input/send,
  live theme refresh without losing the draft.
- Profile media is public in the cache and player pool; cache keys are
  `reels:<account>:profile:<id>` and `reels:<account>:channel:<channelId>:<messageId>`.
- `SharedMediaLayout.TAB_REELS` no longer collides with `TAB_POLL`.
- Upload progress uses the Reels accent token.

## v2 client pass

The profile Reels client now also includes:

- For You / Following feed modes and profile-scoped pagination; channel items resolve
  lazily around the visible page instead of blocking the whole feed.
- Save, share counting, follow/unfollow, creator header and a saved-Reels list.
- Threaded profile comments with replies, GIF upload, likes, pin/unpin and delete;
  counts reconcile against the Reel detail response.
- Upload duration as an integer plus an optional cover-time selector.
- `/r/{id}` and `owpg://.../r/{id}` routing, plus view and share reporting.

Remaining work is verification, not feature scaffolding:

- Compile and exercise the flow on an Android device/emulator; this workspace has no
  Android Gradle/JDK toolchain.
- Replace the URL-based GIF attachment with a native GIF picker if the product wants
  local media selection rather than a URL.
- Confirm the saved-list cursor and comment-reply/pin paths against the deployed
  `reels-client-handoff.md` backend (the handoff file is not present in this checkout).

Do not reintroduce hardcoded colours or a second comments UI while extending it.
