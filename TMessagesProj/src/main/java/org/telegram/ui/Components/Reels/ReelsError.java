package org.telegram.ui.Components.Reels;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/** A small, transport-neutral error value for Reels requests. */
public final class ReelsError {

    public final int status;
    public final String message;

    public ReelsError(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public boolean isNetwork() {
        return status == 0;
    }

    public boolean isAuth() {
        return status == 401 || status == 403;
    }

    public boolean isNotFound() {
        return status == 404;
    }

    /**
     * The server could not decode reels.* at the negotiated layer: either the
     * backend predates the Reels namespace or this session's layer evidence is
     * older than the one that introduced it. Both are recoverable by the
     * operator/relogin, never by the viewer.
     */
    public boolean isUnavailable() {
        return status == 501 || isLayerMismatch();
    }

    private boolean isLayerMismatch() {
        return status == 400 && message != null && message.contains("CONNECTION_LAYER_INVALID");
    }

    public static ReelsError network() {
        return new ReelsError(0, null);
    }

    /** Maps an MTProto RPC failure onto the Reels error surface. */
    public static ReelsError fromTl(TLObject error) {
        if (error == null) {
            return null;
        }
        if (error instanceof TLRPC.TL_error) {
            final TLRPC.TL_error tlError = (TLRPC.TL_error) error;
            return new ReelsError(tlError.code, tlError.text);
        }
        return new ReelsError(500, null);
    }

    public String describe() {
        if (isNetwork()) {
            return "Check your connection and try again.";
        }
        if (isAuth()) {
            return "Your session needs refreshing. Try again.";
        }
        if (isNotFound()) {
            return "This Reel is no longer available.";
        }
        if (isUnavailable()) {
            return "Reels is not available on this server yet.";
        }
        if (isRateLimited()) {
            return "Too many requests. Wait a moment and try again.";
        }
        return userMessage() != null ? userMessage() : "Something went wrong.";
    }

    /**
     * TL error texts are protocol identifiers (CONNECTION_LAYER_INVALID,
     * FLOOD_WAIT_3), not copy. Only pass through a server sentence a person
     * could have written; anything identifier-shaped becomes generic.
     */
    private String userMessage() {
        if (message == null) {
            return null;
        }
        final String text = message.trim();
        if (text.length() < 4 || text.length() > 140) {
            return null;
        }
        if (text.indexOf('_') >= 0) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (Character.isLetter(c) && !Character.isLowerCase(c) && !Character.isUpperCase(c)) {
                return null;
            }
            if (Character.isUpperCase(c) && i > 0 && Character.isUpperCase(text.charAt(i - 1))) {
                return null;
            }
        }
        return text;
    }

    private boolean isRateLimited() {
        return status == 420 || status == 429;
    }
}
