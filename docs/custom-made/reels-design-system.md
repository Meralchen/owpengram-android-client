# Reels design system

Status: authoritative for Reels visuals. Applies to the vertical viewer, the profile
Reels tab, comment sheets, upload chrome and any future Reels surface.

## Why

Telegram already owns the palette, typography and spacing conventions. Reels must look
like part of the app, not a separate product skinned on top. At the same time a design
change — a different accent, a larger grid radius, new sheet spacing — must apply in one
place and be picked up everywhere.

The single source of truth is:

`TMessagesProj/src/main/java/org/telegram/ui/Components/Reels/ReelsTheme.java`

No Reels view should hardcode a colour, corner radius or action size. Read a token.

## Two surfaces, two rules

| Surface | Rule | Source |
|---|---|---|
| Viewer stage (video page, progress, action rail, creator/caption) | Always dark. Telegram's story/photo viewer behaves the same in every theme. | `ReelsTheme.viewer*` constants |
| Telegram surfaces (profile grid, comments sheet, upload chrome, buttons) | Follow the active theme and the screen's `Theme.ResourcesProvider`. | `ReelsTheme` methods |

The viewer chrome is white because it sits on video, not because it ignores the theme.
Its scrim, track, heart red and spacing are tokens too.

## Tokens

### Viewer

| Token | Use |
|---|---|
| `viewerBackground` | Stage behind the video |
| `viewerPrimary` | Icons, captions, active progress, elapsed time |
| `viewerSecondary` | Subtitles, total time, empty/error copy |
| `viewerTrack` | Inactive progress line |
| `viewerScrimTop` / `viewerScrimTopMid` | Top legibility gradient |
| `viewerScrimBottom` / `viewerScrimBottomMid` | Bottom legibility gradient |
| `viewerLike` | Liked heart and double-tap heart |
| `viewerShadow` | Text shadows and floating error pill over video |
| `viewerPlaceholder` | Empty media stage |

### Telegram palette roles

All take the nullable `Theme.ResourcesProvider` of the screen that opened Reels.

| Method | Telegram key | Use |
|---|---|---|
| `background` | `key_windowBackgroundWhite` | Grid and profile page background |
| `surface` | `key_windowBackgroundGray` | Secondary surfaces |
| `textPrimary` | `key_windowBackgroundWhiteBlackText` | Grid/sheet primary text |
| `textSecondary` | `key_windowBackgroundWhiteGrayText` | Empty and hint copy |
| `accent` | `key_featuredStickers_addButton` | Follow pill, `+ Reel`, upload progress |
| `accentText` | `key_featuredStickers_buttonText` | Text on accent buttons |
| `divider` | `key_divider` | Sheet handle, input divider |
| `sheetBackground` | `key_dialogBackground` | Bottom sheet surface |
| `sheetText` | `key_dialogTextBlack` | Sheet titles and comment text |
| `sheetHint` | `key_dialogTextHint` | Comment input hint |
| `sheetAccent` | `key_dialogButton` | Send/close actions |
| `progress` | `key_progressCircle` | Sheet loading spinner |
| `liked` | `key_text_RedBold` | Theme-aware liked state outside the viewer |

### Metrics

`ReelsTheme.sideMargin`, `avatarSize`, `actionIconSize`, `touchTarget`, `gridGap`,
`gridRadius` and `progressHeight` are the shared dp values. Use them instead of new
local constants.

`ReelsTheme.rounded(color, radiusDp)` is the one helper for pills and cells. Change the
radius argument in the caller only if that specific shape differs; change the token if
all shapes should change.

## Theme changes

Every surface that reads Telegram colours observes `NotificationCenter.didSetNewTheme`:

- `ReelsProfileGridView` re-applies colours and rebinds cells.
- `ReelsCommentsSheet` and `ReelsProfileCommentsSheet` re-apply the sheet background,
  handle, text, hint, send action and spinner without losing draft text.
- `ReelsViewer` re-applies chrome and rebinds pages.
- `ReelsUploadController` dialogs inherit the current dialog theme at creation.

Pass the `ResourcesProvider` down from the host (`SharedMediaLayout` passes its profile
provider into `ReelsProfileGridView`; `BaseFragment.resourceProvider` is passed into the
comment sheets). Do not read a global colour when a provider is available.

When adding a new Reels surface:

1. Add or reuse a role in `ReelsTheme`; do not add a local hex value.
2. Pass the provider through the constructor.
3. Register for `didSetNewTheme` and update in place.
4. Use `AndroidUtilities.bold()` for emphasis and `AndroidUtilities.dp` for spacing.
5. Keep interactive targets at least `touchTarget` (48 dp).

## What a design change looks like

- Change the accent everywhere: edit `ReelsTheme.accent`.
- Make Reels grid cells rounder: edit `ReelsTheme.gridRadius`.
- Widen the action rail icons: edit `ReelsTheme.actionIconSize`.
- Make the viewer track dimmer: edit `ReelsTheme.viewerTrack`.
- Change the sheet handle colour: edit `ReelsTheme.divider`.

If a change needs a new role, add it to `ReelsTheme` first, then use it in every surface
that needs it. Never patch a single screen with a hardcoded colour.
