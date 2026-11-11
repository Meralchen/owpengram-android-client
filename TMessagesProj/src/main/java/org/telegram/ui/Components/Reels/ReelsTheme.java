package org.telegram.ui.Components.Reels;

import android.graphics.drawable.GradientDrawable;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * Single source of truth for Reels visuals.
 *
 * <p>Reels owns colour roles and dimensions, Telegram owns the palette. Every Reels surface
 * (vertical viewer, profile grid, comments sheet, upload chrome) reads these tokens instead of
 * hardcoding colours, so a theme edit or a design change made here is picked up everywhere.</p>
 *
 * <p>The viewer is a media surface and follows Telegram's story/photo viewer: it stays dark in
 * both themes. Everything that sits on Telegram's normal surfaces (grid, sheets, buttons) uses
 * the active {@link Theme.ResourcesProvider}, so per-screen themes and night mode apply.</p>
 */
public final class ReelsTheme {

    private ReelsTheme() {
    }

    // ---- Viewer surface (always dark, like Telegram's media viewer) ----

    public static final int viewerBackground = 0xFF000000;
    public static final int viewerPrimary = 0xFFFFFFFF;
    public static final int viewerSecondary = 0xB3FFFFFF;
    public static final int viewerTrack = 0x47FFFFFF;
    public static final int viewerScrimTop = 0x99000000;
    public static final int viewerScrimTopMid = 0x33000000;
    public static final int viewerScrimBottom = 0xCC000000;
    public static final int viewerScrimBottomMid = 0x66000000;
    public static final int viewerLike = 0xFFFF3B30;
    public static final int viewerShadow = 0x66000000;
    public static final int viewerPlaceholder = 0xFF1C1C1E;

    // ---- Metrics shared by every Reels surface ----

    public static final int sideMargin = 16;
    public static final int avatarSize = 36;
    public static final int actionIconSize = 28;
    public static final int touchTarget = 48;
    public static final int gridGap = 1;
    public static final int gridRadius = 6;
    public static final float progressHeight = 2.5f;

    // ---- Palette roles backed by the active Telegram theme ----

    public static int background(@Nullable Theme.ResourcesProvider provider) {
        return Theme.getColor(Theme.key_windowBackgroundWhite, provider);
    }

    public static int surface(@Nullable Theme.ResourcesProvider provider) {
        return Theme.getColor(Theme.key_windowBackgroundGray, provider);
    }

    public static int textPrimary(@Nullable Theme.ResourcesProvider provider) {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, provider);
    }

    public static int textSecondary(@Nullable Theme.ResourcesProvider provider) {
        return Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, provider);
    }

    /** Telegram's accent, used for follow pills and the upload button. */
    public static int accent(@Nullable Theme.ResourcesProvider provider) {
        return Theme.getColor(Theme.key_featuredStickers_addButton, provider);
    }

    public static int accentText(@Nullable Theme.ResourcesProvider provider) {
        return Theme.getColor(Theme.key_featuredStickers_buttonText, provider);
    }

    public static int divider(@Nullable Theme.ResourcesProvider provider) {
        return Theme.getColor(Theme.key_divider, provider);
    }

    public static int sheetBackground(@Nullable Theme.ResourcesProvider provider) {
        return Theme.getColor(Theme.key_dialogBackground, provider);
    }

    public static int sheetText(@Nullable Theme.ResourcesProvider provider) {
        return Theme.getColor(Theme.key_dialogTextBlack, provider);
    }

    public static int sheetHint(@Nullable Theme.ResourcesProvider provider) {
        return Theme.getColor(Theme.key_dialogTextHint, provider);
    }

    public static int sheetAccent(@Nullable Theme.ResourcesProvider provider) {
        return Theme.getColor(Theme.key_dialogButton, provider);
    }

    public static int progress(@Nullable Theme.ResourcesProvider provider) {
        return Theme.getColor(Theme.key_progressCircle, provider);
    }

    public static int liked(@Nullable Theme.ResourcesProvider provider) {
        return Theme.getColor(Theme.key_text_RedBold, provider);
    }

    /** One rounding helper so every Reels pill/cell corner changes together. */
    public static GradientDrawable rounded(int color, float radiusDp) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(AndroidUtilities.dp(radiusDp));
        return drawable;
    }
}
