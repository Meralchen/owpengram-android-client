package org.telegram.ui.Components.Reels;

import android.content.Context;

import androidx.media3.datasource.DefaultHttpDataSource;

/** Public HTTP media only. No MTProto file loader or channel document is used by Reels. */
public final class ReelsCache {

    private ReelsCache() {
    }

    public static DefaultHttpDataSource.Factory dataSourceFactory(Context context) {
        return new DefaultHttpDataSource.Factory()
                .setUserAgent("OWPengram-Reels")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true);
    }

    public static void clearAccount(int account) {
        // Media is intentionally not persisted by this cache; no credentials or channel keys exist here.
    }
}
