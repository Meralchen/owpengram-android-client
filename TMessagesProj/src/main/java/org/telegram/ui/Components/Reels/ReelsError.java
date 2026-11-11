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
        return message != null && message.length() > 0 ? message : "Something went wrong.";
    }
}
