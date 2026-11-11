package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.net.Uri;
import android.view.TextureView;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Bounded vertical player pool: the current clip plus its neighbours, never more than
 * {@link #MAX_PLAYERS} ExoPlayer instances. Profile media is public, so no authorization
 * handshake is needed before playback can start.
 */
@OptIn(markerClass = UnstableApi.class)
public final class ReelsPlayerPool {

    public static final int MAX_PLAYERS = 3;
    private static final int AHEAD = 1;
    private static final int BEHIND = 1;

    public interface Listener {
        void onPlayerReady();
    }

    private static final class Entry {
        final ExoPlayer player;
        final ReelsItem item;
        TextureView view;

        Entry(ExoPlayer player, ReelsItem item) {
            this.player = player;
            this.item = item;
        }
    }

    private final Context app;
    private final Listener listener;
    private final Map<Integer, Entry> pool = new HashMap<>();
    private int current = -1;
    private boolean muted;

    public ReelsPlayerPool(Context context, Listener listener) {
        app = context.getApplicationContext();
        this.listener = listener;
    }

    /** Returns the prepared (paused) player for a clip, creating it if it is within the window. */
    public ExoPlayer acquire(int position, ReelsItem item) {
        if (current >= 0 && (position < current - BEHIND || position > current + AHEAD)) {
            return null;
        }
        Entry entry = pool.get(position);
        if (entry != null && entry.item != item) {
            releaseEntry(pool.remove(position));
            entry = null;
        }
        if (entry == null) {
            final ExoPlayer player = create(item);
            if (player == null) {
                return null;
            }
            entry = new Entry(player, item);
            pool.put(position, entry);
            trim();
        }
        return entry.player;
    }

    public ExoPlayer get(int position) {
        final Entry entry = pool.get(position);
        return entry != null ? entry.player : null;
    }

    /** Moves the vertical window, pausing and releasing players outside the current neighbours. */
    public void setCurrent(int position) {
        current = position;
        pauseAll();
        trim();
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        for (Entry entry : pool.values()) {
            entry.player.setVolume(muted ? 0f : 1f);
        }
    }

    public boolean isMuted() {
        return muted;
    }

    /** Detaches the player from whatever view it had before rendering into the new one. */
    public void attach(int position, TextureView view) {
        final Entry entry = pool.get(position);
        if (entry == null || entry.view == view) {
            return;
        }
        for (Entry other : pool.values()) {
            if (other != entry && other.view == view) {
                other.player.clearVideoTextureView(view);
                other.view = null;
            }
        }
        if (entry.view != null) {
            entry.player.clearVideoTextureView(entry.view);
        }
        entry.view = view;
        entry.player.setVideoTextureView(view);
    }

    public void detach(TextureView view) {
        for (Entry entry : pool.values()) {
            if (entry.view == view) {
                entry.player.clearVideoTextureView(view);
                entry.view = null;
            }
        }
    }

    public void pauseAll() {
        for (Entry entry : pool.values()) {
            entry.player.pause();
        }
    }

    public void release() {
        for (Entry entry : pool.values()) {
            releaseEntry(entry);
        }
        pool.clear();
        current = -1;
    }

    private void trim() {
        final ArrayList<Integer> remove = new ArrayList<>();
        for (Integer position : pool.keySet()) {
            if (current >= 0 && (position < current - BEHIND || position > current + AHEAD)) {
                remove.add(position);
            }
        }
        for (Integer position : remove) {
            releaseEntry(pool.remove(position));
        }
        while (pool.size() > MAX_PLAYERS) {
            Integer farthest = null;
            for (Integer position : pool.keySet()) {
                if (farthest == null || Math.abs(position - current) > Math.abs(farthest - current)) {
                    farthest = position;
                }
            }
            if (farthest == null) {
                break;
            }
            releaseEntry(pool.remove(farthest));
        }
    }

    private void releaseEntry(Entry entry) {
        if (entry == null) {
            return;
        }
        entry.item.resumePositionMs = entry.player.getCurrentPosition();
        if (entry.view != null) {
            entry.player.clearVideoTextureView(entry.view);
            entry.view = null;
        }
        entry.player.release();
    }

    private ExoPlayer create(ReelsItem item) {
        final Uri uri = item.source == ReelsItem.Source.PROFILE
                ? (item.mediaUrl != null ? Uri.parse(item.mediaUrl) : null)
                : item.mtprotoUri();
        if (uri == null) {
            return null;
        }
        final DefaultRenderersFactory renderers = new DefaultRenderersFactory(app);
        renderers.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        final ExoPlayer player = new ExoPlayer.Builder(app, renderers).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setPlayWhenReady(false);
        player.setVolume(muted ? 0f : 1f);
        player.setMediaSource(new ProgressiveMediaSource.Factory(ReelsCache.dataSourceFactory(app, item))
                .createMediaSource(MediaItem.fromUri(uri)));
        if (item.resumePositionMs > 0) {
            player.seekTo(item.resumePositionMs);
        }
        player.prepare();
        return player;
    }
}
