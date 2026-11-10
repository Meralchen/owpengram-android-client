package org.telegram.ui.Components.Reels;

import android.content.Context;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileStreamLoadOperation;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Process-wide LRU cache (<= 512 MiB, private app dir) for channel MTProto streams and
 * authenticated profile-Reel media. Keys are account-scoped so logout drops only that account.
 */
@OptIn(markerClass = UnstableApi.class)
public final class ReelsCache {

    private static final long MAX_BYTES = 512L * 1024 * 1024;

    private static volatile SimpleCache cache;

    private ReelsCache() {
    }

    public static SimpleCache get(Context context) {
        SimpleCache local = cache;
        if (local == null) {
            synchronized (ReelsCache.class) {
                local = cache;
                if (local == null) {
                    final Context app = context.getApplicationContext();
                    final File dir = new File(app.getCacheDir(), "reels");
                    local = cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(MAX_BYTES), new StandaloneDatabaseProvider(app));
                }
            }
        }
        return local;
    }

    static String keyPrefix(int account) {
        return "reels:" + account + ":";
    }

    static String profileKey(int account, String reelId) {
        return "reels-profile:" + account + ":" + reelId;
    }

    /** Keeps channel media on MTProto and profile media on authenticated byte-range HTTP. */
    public static DataSource.Factory dataSourceFactory(Context context, ReelsItem item) {
        final String key = item.cacheKey();
        final DataSource.Factory upstream;
        if (item.source == ReelsItem.Source.PROFILE) {
            final DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory();
            if (item.playbackAuthorizationHeader != null) {
                http.setDefaultRequestProperties(Collections.singletonMap("Authorization", item.playbackAuthorizationHeader));
            }
            upstream = http;
        } else {
            upstream = FileStreamLoadOperation::new;
        }
        return new CacheDataSource.Factory()
                .setCache(get(context))
                .setUpstreamDataSourceFactory(upstream)
                .setCacheKeyFactory(dataSpec -> key)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    /** Called on logout: removes the account's cached clips without blocking the caller. */
    public static void clearAccount(int account) {
        final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "reels");
        if (cache == null && !dir.exists()) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final SimpleCache c = get(ApplicationLoader.applicationContext);
                final String prefix = keyPrefix(account);
                final String profilePrefix = "reels-profile:" + account + ":";
                for (String key : new ArrayList<>(c.getKeys())) {
                    if (key.startsWith(prefix) || key.startsWith(profilePrefix)) {
                        c.removeResource(key);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }
}
