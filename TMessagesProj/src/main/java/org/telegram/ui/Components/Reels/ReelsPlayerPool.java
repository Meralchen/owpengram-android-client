package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.view.TextureView;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** A small current/next ExoPlayer pool for the horizontal Story pager. */
public final class ReelsPlayerPool {

    private static final class Entry {
        final ExoPlayer player;
        final String itemId;

        Entry(ExoPlayer player, String itemId) {
            this.player = player;
            this.itemId = itemId;
        }
    }

    private final Context context;
    private final HashMap<Integer, Entry> entries = new HashMap<>();
    private int current = -1;

    public ReelsPlayerPool(Context context) {
        this.context = context.getApplicationContext();
    }

    public ExoPlayer acquire(int position, ReelsItem item) {
        if (item == null || item.mediaUrl == null) return null;
        Entry existing = entries.get(position);
        if (existing != null && !item.id.equals(existing.itemId)) {
            release(position);
            existing = null;
        }
        if (existing == null) {
            final ExoPlayer player = new ExoPlayer.Builder(context).build();
            player.setHandleAudioBecomingNoisy(true);
            final ProgressiveMediaSource source = new ProgressiveMediaSource.Factory(
                    ReelsCache.dataSourceFactory(context))
                    .createMediaSource(MediaItem.fromUri(item.mediaUrl));
            player.setMediaSource(source);
            player.prepare();
            if (item.resumePositionMs > 0) {
                player.seekTo(item.resumePositionMs);
            }
            existing = new Entry(player, item.id);
            entries.put(position, existing);
        }
        return existing.player;
    }

    public ExoPlayer get(int position) {
        final Entry entry = entries.get(position);
        return entry == null ? null : entry.player;
    }

    public void attach(int position, TextureView textureView) {
        final Entry entry = entries.get(position);
        if (entry != null && textureView != null) {
            entry.player.setVideoTextureView(textureView);
        }
    }

    public void setCurrent(int position) {
        current = position;
        final List<Integer> stale = new ArrayList<>();
        for (Integer key : entries.keySet()) {
            if (key < position - 1 || key > position + 1) {
                stale.add(key);
            }
        }
        for (Integer key : stale) {
            release(key);
        }
    }

    public void pauseAllExcept(int position) {
        for (HashMap.Entry<Integer, Entry> entry : entries.entrySet()) {
            if (entry.getKey() != position) {
                entry.getValue().player.pause();
            }
        }
    }

    public void release(int position) {
        final Entry entry = entries.remove(position);
        if (entry != null) {
            entry.player.setVideoTextureView(null);
            entry.player.release();
        }
    }

    public void releaseAll() {
        for (Integer position : new ArrayList<>(entries.keySet())) {
            release(position);
        }
        current = -1;
    }
}
