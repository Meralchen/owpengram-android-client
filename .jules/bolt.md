# Bolt's Journal

Critical learnings only.

## 2026-11-10 - Reels Timeline Invalidation
**Learning:** In high-frequency video playback loops (e.g. 16ms / 60 FPS), timeline progress views should cache the last rendered pixel width to prevent redundant `invalidate()` calls and layout/draw overhead when the rendered progress bar does not change.
**Action:** Always check if the computed progress bar pixel width has advanced before calling `invalidate()`.
