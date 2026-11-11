package org.telegram.ui.Components.Reels;

import org.json.JSONObject;

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

    public static ReelsError fromResponse(int status, String body) {
        if (status >= 200 && status < 300) {
            return null;
        }
        String message = null;
        if (body != null && body.length() > 0) {
            try {
                JSONObject object = new JSONObject(body);
                message = object.optString("error", object.optString("message", null));
            } catch (Exception ignore) {
            }
        }
        return new ReelsError(status, message);
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
