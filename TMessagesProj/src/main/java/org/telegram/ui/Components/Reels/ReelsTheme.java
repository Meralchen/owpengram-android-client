package org.telegram.ui.Components.Reels;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import org.telegram.ui.ActionBar.Theme;

/** Theme roles shared by the Reels stage and its normal-theme sheets. */
public final class ReelsTheme {

    public static final int STAGE_BACKGROUND = Color.BLACK;
    public static final int STAGE_TEXT = Color.WHITE;
    public static final int STAGE_TEXT_SECONDARY = 0xB3FFFFFF;
    public static final int STAGE_PILL = 0x33FFFFFF;
    public static final int STAGE_SCRIM = 0x99000000;
    public static final int STAGE_DANGER = 0xFFFF6B6B;

    private ReelsTheme() {
    }

    public static int color(int key, Theme.ResourcesProvider provider) {
        return provider == null ? Theme.getColor(key) : Theme.getColor(key, provider);
    }

    public static int background(Theme.ResourcesProvider provider) {
        return color(Theme.key_windowBackgroundWhite, provider);
    }

    public static int textPrimary(Theme.ResourcesProvider provider) {
        return color(Theme.key_windowBackgroundWhiteBlackText, provider);
    }

    public static int textSecondary(Theme.ResourcesProvider provider) {
        return color(Theme.key_windowBackgroundWhiteGrayText, provider);
    }

    public static int divider(Theme.ResourcesProvider provider) {
        return color(Theme.key_divider, provider);
    }

    public static int dialogBackground(Theme.ResourcesProvider provider) {
        return color(Theme.key_dialogBackground, provider);
    }

    public static int dialogText(Theme.ResourcesProvider provider) {
        return color(Theme.key_dialogTextBlack, provider);
    }

    public static int dialogSecondary(Theme.ResourcesProvider provider) {
        return color(Theme.key_dialogTextGray2, provider);
    }

    public static int accent(Theme.ResourcesProvider provider) {
        return color(Theme.key_chats_actionBackground, provider);
    }

    public static int accentIcon(Theme.ResourcesProvider provider) {
        return color(Theme.key_chats_actionIcon, provider);
    }

    public static GradientDrawable rounded(int color, float radius) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    public static GradientDrawable outlined(int fill, int stroke, float radius, int strokeWidth) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(strokeWidth, stroke);
        return drawable;
    }
}
