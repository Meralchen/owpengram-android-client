package org.telegram.ui.Components.Reels;

/** The only sources a Reels viewer can use. Profile Reels never become a channel feed. */
public enum ReelsFeedSource {
    FOR_YOU("for_you", false, false),
    LATEST("latest", false, false),
    FOLLOWING("following", true, false),
    PROFILE(null, false, false),
    SAVED(null, true, false),
    SINGLE(null, false, true);

    public final String mode;
    public final boolean requiresAuth;
    public final boolean singleItem;

    ReelsFeedSource(String mode, boolean requiresAuth, boolean singleItem) {
        this.mode = mode;
        this.requiresAuth = requiresAuth;
        this.singleItem = singleItem;
    }

    public boolean isGlobalFeed() {
        return this == FOR_YOU || this == LATEST || this == FOLLOWING;
    }

    public boolean isBounded() {
        return this == PROFILE || this == SAVED || this == SINGLE;
    }

    public boolean isProfile() {
        return this == PROFILE;
    }
}
