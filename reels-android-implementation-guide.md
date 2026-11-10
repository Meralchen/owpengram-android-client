# Reels Feature: Android Implementation & Architecture Guide (Horizontal Swipe)

> **Specification & Architecture Blueprint**  
> **Status:** Overrides & supersedes legacy vertical shorts implementation.  
> **Primary Navigation:** Horizontal Snap Paging (`Right-to-Left` for Next, `Left-to-Right` for Previous).

---

## 1. Executive Summary & Design Vision

This document is the definitive technical guide for implementing **Reels** in the Owpengram Android client. 

Reels is a dedicated, infinite short-form video platform that deliberately breaks away from generic vertical feeds by adopting **horizontal snap navigation**, inspired by Telegram's native Story mechanics and high-end horizontal media viewers (such as `backend/contrib/horizontal-shorts`).

### 1.1 Core Principles

1. **Horizontal Snap Navigation**:
   - Paging between video clips occurs strictly along the **horizontal axis** (`Right-to-Left` to advance, `Left-to-Right` to go back).
   - The vertical axis is freed for natural contextual interactions:
     - **Swipe Up**: Opens comments drawer / bottom sheet.
     - **Swipe Down / Pull down**: Dismisses viewer or navigates to creator channel/profile with spring-damping dismiss physics.
2. **Dual-Source Engine**:
   - **Profile-Owned Reels (`source: "profile"`)**: Uploaded to creator profiles, stored on the server, streamed via HTTP byte-range requests (`/api/reels/{reelId}/media`), with comments and likes handled via REST APIs.
   - **Channel Shorts (`source: "channel"`)**: Video messages posted in public channels, streamed over MTProto via `upload.getFile` / `FileStreamLoadOperation`, with reactions synced to MTProto `messages.sendReaction` and comments routed to channel discussion threads.
3. **Overriding Legacy Vertical Shorts**:
   - This architecture completely replaces the legacy vertical-scrolling `Shorts*` components with a dedicated horizontal engine (`ReelsViewer`, `ReelsViewPager`, `ReelsPageView`, `ReelsPlayerPool`).
4. **Instant Zero-Buffer Playback**:
   - Bounded horizontal player pool pre-warms adjacent video clips (left and right neighbors) so horizontal swipes start video frames immediately with zero visible delay.

---

## 2. Interaction Model & Gesture Physics

Navigation and physics adhere strictly to Telegram's motion curves (`CubicBezierInterpolator.EASE_OUT_QUINT` and AndroidX dynamic spring animations).

```
                      ▲ Vertical Swipe Up (Pull Up)
                      │ ──> Open Comments BottomSheet
                      │
◀─────────────────────┼─────────────────────▶
  Horizontal Swipe    │    Horizontal Swipe
   (Left to Right)    │     (Right to Left)
  ──> PREVIOUS REEL   │     ──> NEXT REEL
                      │
                      │
                      ▼ Vertical Swipe Down (Pull Down)
                        ──> Dismiss Viewer / Open Channel or Profile
```

### Complete Gesture Matrix

| Gesture | Action | Motion & Animation Behavior |
| :--- | :--- | :--- |
| **Horizontal Swipe (Right $\rightarrow$ Left)** | **Next Reel** | Smooth horizontal snap pagination (`ViewPager2` or custom horizontal `RecyclerView` + `PagerSnapHelper`). Current video pauses and transitions out; next preloaded video begins playing immediately. |
| **Horizontal Swipe (Left $\rightarrow$ Right)** | **Previous Reel** | Smooth horizontal snap back to the previous video. Restores playback timestamp seamlessly. |
| **Vertical Swipe (Down $\rightarrow$ Up)** | **Open Comments** | Slides up comments `BottomSheet` over the video with spring damping. Video continues playing (optionally dimmed by 10% or scaled down to 96%). |
| **Vertical Swipe (Up $\rightarrow$ Down)** | **Dismiss / Pull to Profile** | If dragged downward from media/header: triggers Telegram's story dismiss animation (spring-backed pinch/pull with scale $1.0 \to 0.85$ and alpha fade), popping back to the previous screen or opening the creator's profile/channel. |
| **Single Tap** | **Play / Pause** | Toggles video playback. Spawns an animated center play/pause badge icon that scales up ($0.8 \to 1.1$) and fades out over 220ms. |
| **Double Tap** | **Like Video** | Spawns a floating heart popup animation (`heart-pop`) at the tap coordinates that scales up to 125%, bounces, and fades out over 800ms. Fills the heart action icon with red fill (`❤️`) and increments count. |
| **Long Press (Hold)** | **Clean Viewing Mode** | Smoothly fades out all UI overlays (timeline, creator badge, caption, action buttons) to alpha 0. Releasing restores the overlay to alpha 1. |
| **Tap / Drag Progress Bar**| **Seek Playback** | Top timeline bar shows active playback progress. Tapping or scrubbing immediately seeks within the active `ExoPlayer` instance. |

---

## 3. Architecture & Class Structure

The new horizontal engine lives under `org.telegram.ui.Components.Reels` (superseding `org.telegram.ui.Components.Shorts`):

```text
TMessagesProj/src/main/java/org/telegram/ui/Components/Reels/
├── ReelsViewer.java            // Fullscreen container, coordinator & lifecycle manager
├── ReelsViewPager.java         // Horizontal snap pager (scroll-snap-type: x mandatory)
├── ReelsPageView.java          // Single clip view (TextureView, overlay, timeline, controls)
├── ReelsPlayerPool.java        // Bounded horizontal player pool (Current, Left, Right)
├── ReelsCache.java             // 512 MiB process-wide LRU disk cache (Media3 SimpleCache)
├── ReelsItem.java              // Unified data model (Profile & Channel)
├── ReelsRepository.java        // Infinite feed manager, cursor pagination, link resolution
├── ReelsBackendApi.java        // MTProto auth bridge & HTTP REST client
├── ReelsCommentsSheet.java     // Unified comments BottomSheet (REST or MTProto discussion)
├── ReelsUploadController.java  // Background resumable chunk uploader (512 KiB parts)
└── ReelsProfileGridView.java   // 3-column profile tab grid with '+ Reel' button
```

### Component Architecture Diagram

```
                               ┌──────────────────────────────┐
                               │       ReelsViewer.java       │
                               │  (Fullscreen Host Fragment)  │
                               └──────────────┬───────────────┘
                                              │
                     ┌────────────────────────┴────────────────────────┐
                     ▼                                                 ▼
          ┌─────────────────────┐                           ┌─────────────────────┐
          │   ReelsViewPager    │                           │   ReelsPlayerPool   │
          │  (Horizontal Snap)  │                           │  (Current / ±1 Pre) │
          └──────────┬──────────┘                           └──────────┬──────────┘
                     │                                                 │
          ┌──────────┴──────────┐                                      │
          ▼                     ▼                                      │
  ┌───────────────┐     ┌───────────────┐                              │
  │ ReelsPageView │ ... │ ReelsPageView │                              │
  │  (Position 0) │     │  (Position 1) │                              │
  └───────┬───────┘     └───────────────┘                              │
          │                                                            │
          └─────────────────────┬──────────────────────────────────────┘
                                ▼
                       TextureView Attachment
```

### Class Responsibilities

1. **`ReelsViewer`**:
   - Manages top-level fullscreen presentation, status bar coloring, volume ducking, and dismissal transitions.
   - Connects `ReelsViewPager` with `ReelsPlayerPool`.
2. **`ReelsViewPager`**:
   - Horizontal snap pager configured for `HORIZONTAL` orientation.
   - Enforces gesture arbitration: horizontal swipes drive page transitions; vertical gestures are intercepted for comments (swipe up) and dismissal (swipe down).
3. **`ReelsPageView`**:
   - Hosts the video `TextureView`, thumbnail poster `BackupImageView`, top timeline bar, creator avatar and name, expandable caption, and right-hand action column (Like, Comment, Share, Views).
4. **`ReelsPlayerPool`**:
   - Maintains a maximum of **3 active `ExoPlayer` instances**:
     - `Index - 1`: Left neighbor (preloaded).
     - `Index`: Active visible Reel (playing).
     - `Index + 1`: Right neighbor (preloaded).
   - Moving horizontally promotes the target player to active and recycles the farthest instance.
5. **`ReelsCache`**:
   - Shared 512 MiB `SimpleCache` disk cache with account-scoped keys (`reels:<account>:<id>`).

---

## 4. Complete Backend REST API Reference

**Base URL**: Derived from the active server configuration (`MessagesController.getInstance(account).linkPrefix`, e.g., `https://api.owpengram.org`).

### 4.1 Authentication Bridge

All authenticated REST calls use the user's active MTProto session via exported authorization:

```
1. Client calls MTProto: auth.exportAuthorization(dcId = 0)
2. Receives: auth.ExportedAuthorization{ id: <userId>, bytes: <tokenBytes> }
3. Formats Header:
   Authorization: Telegram <userId>:<base64url(tokenBytes)>
```

- Tokens are held strictly in memory with a 4-minute expiry cache.
- Raw exported token bytes are never logged or persisted to disk.

---

### 4.2 Resumable Multipart Upload Workflow

Profile-owned Reels are uploaded in 512 KiB chunks with a scoped, short-lived bearer ticket:

```
Client                                                         Server
  │                                                              │
  │ 1. POST /api/profiles/me/reels/uploads ─────────────────────>│
  │    (idempotencyKey, sizeBytes, mimeType, duration, caption)  │
  │<── 201 Created (uploadId, ticket, partSize=524288) ──────────│
  │                                                              │
  │ 2. PUT /api/profiles/me/reels/uploads/{id}/parts/0 ─────────>│
  │    (Authorization: Bearer <ticket>, 512KB raw binary)        │
  │<── 204 No Content ───────────────────────────────────────────│
  │    ... [repeat for parts 1 .. N-1] ...                       │
  │                                                              │
  │ 3. POST /api/profiles/me/reels/uploads/{id}/complete ───────>│
  │    (Authorization: Bearer <ticket>)                          │
  │<── 200 OK (reelId, state: "ready") ──────────────────────────│
```

#### Step 1: Request Upload Session
- **Endpoint:** `POST /api/profiles/me/reels/uploads`
- **Headers:** `Authorization: Telegram <userId>:<token>`, `Content-Type: application/json`
- **Body:**
  ```json
  {
    "idempotencyKey": "9c1b8f2e-4a6c-4e1b-2d3e-4f5a6b7c8d9e",
    "sizeBytes": 24510000,
    "mimeType": "video/mp4",
    "duration": 28.5,
    "width": 1080,
    "height": 1920,
    "caption": "Night run in Neo-Tokyo 🏎️ #drive"
  }
  ```
- **Response (`201 Created`):**
  ```json
  {
    "uploadId": "up_718293049182",
    "ticket": "scoped-bearer-upload-token",
    "state": "pending",
    "expiresAt": "2026-11-10T23:00:00Z",
    "partSize": 524288
  }
  ```

#### Step 2: Upload Binary Parts
- **Endpoint:** `PUT /api/profiles/me/reels/uploads/{uploadId}/parts/{partNumber}`
- **Headers:**
  - `Authorization: Bearer <ticket>`
  - `Content-Type: application/octet-stream`
- **Body:** Raw binary slice of part `partNumber` (512 KiB).
- **Response:** `204 No Content`.

#### Step 3: Complete & Commit
- **Endpoint:** `POST /api/profiles/me/reels/uploads/{uploadId}/complete`
- **Headers:** `Authorization: Bearer <ticket>`
- **Response (`200 OK`):**
  ```json
  {
    "reelId": "reel_718293049182",
    "state": "ready"
  }
  ```

#### Step 4: Cancel Upload
- **Endpoint:** `DELETE /api/profiles/me/reels/uploads/{uploadId}`
- **Headers:** `Authorization: Bearer <ticket>`
- **Response:** `204 No Content`.

---

### 4.3 Feed & Profile Endpoints

#### Infinite Horizontal Feed
- **Endpoint:** `GET /api/reels/feed?limit=20&cursor={nextCursor}`
- **Headers:** `Authorization: Telegram <userId>:<token>` (Optional)
- **Response (`200 OK`):**
  ```json
  {
    "items": [
      {
        "id": "reel_718293049182",
        "source": "profile",
        "owner": {
          "username": "elena",
          "displayName": "Elena Rostova"
        },
        "caption": "Exploring the fjords #norway #travel",
        "mediaUrl": "/api/reels/reel_718293049182/media",
        "thumbnailUrl": "/api/reels/reel_718293049182/thumbnail",
        "duration": 22.0,
        "width": 1080,
        "height": 1920,
        "views": 38400,
        "likes": 2910,
        "comments": 142,
        "liked": true,
        "createdAt": "2026-11-10T19:00:00Z"
      },
      {
        "id": "channel:100987654321:108",
        "source": "channel",
        "channel": {
          "username": "speeddaily",
          "title": "Speed Daily"
        },
        "messageId": 108,
        "caption": "Track day highlights with telemetry",
        "shareUrl": "https://t.me/speeddaily/v/108",
        "thumbnailUrl": null,
        "duration": 58.0,
        "views": 94100,
        "likes": 7820,
        "comments": 401,
        "liked": false,
        "createdAt": "2026-11-10T18:30:00Z"
      }
    ],
    "nextCursor": "eyJsYXN0SWQiOiIxMDgiLCJjcmVhdGVkQXQiOiIyMDI2LTExLTEwVDE4OjMwOjAwWiJ9"
  }
  ```
- *Pass `nextCursor` to fetch subsequent pages. When `nextCursor` is `null`, end of feed is reached.*

#### Profile Reels Listing (Creator Grid)
- **Endpoint:** `GET /api/profiles/{username}/reels?limit=20&offset={offset}`
- **Response (`200 OK`):**
  ```json
  {
    "username": "elena",
    "items": [ /* array of reel objects */ ],
    "nextOffset": 20
  }
  ```

#### Media Playback (Byte-Range Streaming)
- **Endpoint:** `GET /api/reels/{reelId}/media`
- **Headers:** `Authorization: Telegram <userId>:<token>`, `Range: bytes=0-1048575`
- **Response (`206 Partial Content`):**
  ```http
  HTTP/1.1 206 Partial Content
  Content-Type: video/mp4
  Accept-Ranges: bytes
  Content-Range: bytes 0-1048575/24510000
  Content-Length: 1048576
  
  <binary video bytes>
  ```

---

### 4.4 Social Interactions & Comments

#### Like Toggle
- **Endpoint:** `PUT /api/reels/{reelId}/like`
- **Headers:** `Authorization: Telegram <userId>:<token>`, `Content-Type: application/json`
- **Body:** `{"liked": true}`
- **Response (`200 OK`):** `{"liked": true, "likes": 2911}`

#### Record View
- **Endpoint:** `POST /api/reels/{reelId}/view`
- **Body:** `{"positionMs": 15400, "completed": false}`
- **Response (`200 OK`):** `{"ok": true}`

#### List Comments
- **Endpoint:** `GET /api/reels/{reelId}/comments?limit=30&before={commentId}`
- **Response (`200 OK`):**
  ```json
  {
    "items": [
      {
        "id": "cm_48102",
        "userId": 9214,
        "username": "marcus",
        "displayName": "Marcus Vance",
        "text": "The color grading here is unreal!",
        "createdAt": "2026-11-10T19:20:00Z"
      }
    ]
  }
  ```

#### Post Comment
- **Endpoint:** `POST /api/reels/{reelId}/comments`
- **Headers:** `Authorization: Telegram <userId>:<token>`, `Content-Type: application/json`
- **Body:** `{"text": "Phenomenal footage! 🏔️"}`
- **Response (`201 Created`):** Returns created comment object.

#### Delete Comment
- **Endpoint:** `DELETE /api/reels/{reelId}/comments/{commentId}`
- **Headers:** `Authorization: Telegram <userId>:<token>`
- **Response:** `204 No Content`.

---

## 5. MTProto Channel Shorts Integration

When `item.source == Source.CHANNEL`, the media bypasses REST and leverages the MTProto protocol:

1. **Detection:** Verified via `playableVideo(message)`:
   - Must be `TLRPC.TL_messageMediaDocument` with `TLRPC.TL_documentAttributeVideo`.
   - Excludes round video messages (`round_message == true`), voice notes, and GIFs.
2. **Stream URI:**
   ```java
   int fileRef = FileLoader.getInstance(account).getFileReference(messageObject);
   Uri uri = VideoPlayer.VideoUri.getUri(account, document, fileRef);
   ```
   ExoPlayer's data source bridges to `FileStreamLoadOperation` to stream byte blocks via `upload.getFile`.
3. **Likes via Reactions:**
   Dispatches `TLRPC.TL_messages_sendReaction` with emoji `❤️` (`ShortsItem.HEART`).
4. **Comments via Discussion Threads:**
   - Resolve discussion root via `TLRPC.TL_messages_getDiscussionMessage`.
   - Fetch thread replies via `TLRPC.TL_messages_getReplies`.
   - Send reply via `TLRPC.TL_messages_sendMessage` with `reply_to.reply_to_msg_id = rootId`.

---

## 6. Horizontal Media3 ExoPlayer Architecture

### 6.1 Sliding Window Player Pool (`ReelsPlayerPool`)

Horizontal paging requires eager preloading on both sides:

```
   [Position - 1]           [Position (Current)]          [Position + 1]
    Left Neighbor              ▶ ACTIVE PLAYING           Right Neighbor
 Preloaded / Paused                                     Preloaded / Paused
```

- When the user swipes right-to-left to `position + 1`:
  1. `ReelsPlayerPool.setCurrent(position + 1)` promotes player at `position + 1` to active playback.
  2. Player at `position - 1` is outside the window ($< \text{current} - 1$) and is released (`player.release()`).
  3. Preloading starts for `position + 2` on the right.
- **Audio Attributes:** Set to `USAGE_MEDIA` and `AUDIO_CONTENT_TYPE_MOVIE` with system audio focus handling.
- **Looping:** `player.setRepeatMode(Player.REPEAT_MODE_ONE)`.

### 6.2 Disk Caching Pipeline (`ReelsCache`)

- 512 MiB process-wide cache using Media3 `SimpleCache` (`LeastRecentlyUsedCacheEvictor` + `StandaloneDatabaseProvider`).
- Upstream data sources:
  - Profile media: `DefaultHttpDataSource` with `Authorization: Telegram <userId>:<token>`.
  - Channel media: `FileStreamLoadOperation::new`.
- On logout: `ReelsCache.clearAccount(account)` removes account-scoped keys asynchronously.

---

## 7. Migration & Overriding Plan

To replace the legacy vertical shorts implementation:

1. **Replace Main Tab Navigation (`MainTabsActivity.java`)**:
   - Point `TAB_REELS` to instantiate `ReelsViewer` instead of the legacy `ShortsActivity`.
2. **Replace Profile Media Tab (`SharedMediaLayout.java` & `ProfileActivity.java`)**:
   - Bind `TAB_REELS` to `ReelsProfileGridView` with horizontal transition when an item is tapped.
   - Attach upload trigger to `ReelsUploadController`.
3. **Replace Deep Link Routing (`LaunchActivity.java`)**:
   - Route `/{username}/v/{messageId}` to launch `ReelsViewer(startItem)` with horizontal snap paging.
4. **Retire Legacy Components**:
   - Deprecate or remove `TMessagesProj/.../Components/Shorts/*` once `Reels*` components are verified.

---

## 8. Summary Checklist for Implementation

- [ ] Paging is strictly horizontal (`Right-to-Left` advances, `Left-to-Right` goes back).
- [ ] Vertical swipe up smoothly opens comments bottom sheet.
- [ ] Vertical swipe down triggers story-style pull-to-dismiss animation.
- [ ] Double-tap spawns floating heart pop (`❤️`) and toggles like.
- [ ] Pre-warms immediate left and right neighbors for zero buffer delay.
- [ ] Profile Reels stream via byte-range HTTP; channel shorts stream via MTProto.
- [ ] Uploads use 512 KiB resumable chunking with scoped bearer tickets.
- [ ] Fullscreen clean mode activates on long press.
- [ ] Respects system accessibility reduced-motion settings.
