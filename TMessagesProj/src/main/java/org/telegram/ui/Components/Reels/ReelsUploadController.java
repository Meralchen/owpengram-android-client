package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_reels;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * A real foreground Reel upload: the video is uploaded as a Telegram document
 * with upload.saveBigFilePart and then published with reels.create.
 */
public final class ReelsUploadController {

    public interface Callback {
        void onUploaded(ReelsItem item, String error);
    }

    private static final int PART_SIZE = 512 * 1024;
    private static final int MAX_PARTS = 4000;

    private final int account;
    private final Context context;
    private boolean running;
    private boolean cancelled;
    private byte[] bytes;
    private String caption;
    private int coverTimeMs;
    private int duration;
    private int width;
    private int height;
    private String mime;
    private long fileId;
    private int partIndex;
    private int partCount;
    private Callback callback;

    public ReelsUploadController(Context context, int account) {
        this.context = context.getApplicationContext();
        this.account = account;
    }

    public boolean isRunning() {
        return running;
    }

    public void start(Uri uri, String caption, int coverTimeMs, Callback callback) {
        if (running || uri == null) return;
        running = true;
        cancelled = false;
        this.callback = callback;
        this.bytes = null;
        this.partIndex = 0;
        this.partCount = 0;
        new AsyncTask<Void, Void, Object[]>() {
            @Override
            protected Object[] doInBackground(Void... voids) {
                try {
                    final byte[] data = readBytes(uri);
                    final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(context, uri);
                    final int durationMs = parseInt(retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION));
                    final int videoWidth = parseInt(retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    final int videoHeight = parseInt(retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    retriever.release();
                    return new Object[]{data, durationMs, videoWidth, videoHeight};
                } catch (Exception error) {
                    return new Object[]{null, 0, 0, 0};
                }
            }

            @Override
            protected void onPostExecute(Object[] result) {
                if (cancelled) {
                    running = false;
                    return;
                }
                final byte[] data = (byte[]) result[0];
                if (data == null || data.length == 0) {
                    finish(null, "The selected video could not be read.");
                    return;
                }
                bytes = data;
                final int durationMs = (int) result[1];
                final int videoWidth = (int) result[2];
                final int videoHeight = (int) result[3];
                final String type = context.getContentResolver().getType(uri);
                ReelsUploadController.this.caption = TextUtils.isEmpty(caption) ? "" : caption;
                ReelsUploadController.this.coverTimeMs = Math.max(0, coverTimeMs);
                ReelsUploadController.this.duration = Math.max(1, (int) Math.ceil(durationMs / 1000d));
                ReelsUploadController.this.width = Math.max(1, videoWidth);
                ReelsUploadController.this.height = Math.max(1, videoHeight);
                ReelsUploadController.this.mime = TextUtils.isEmpty(type) ? "video/mp4" : type;
                beginUpload();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void beginUpload() {
        partCount = Math.max(1, (bytes.length + PART_SIZE - 1) / PART_SIZE);
        if (partCount > MAX_PARTS) {
            finish(null, "This video file is too large.");
            return;
        }
        fileId = ((long) bytes.length << 20) ^ System.nanoTime();
        if (fileId == 0) fileId = 1;
        partIndex = 0;
        uploadNextPart();
    }

    private void uploadNextPart() {
        if (cancelled) return;
        if (partIndex >= partCount) {
            publish();
            return;
        }
        final int start = partIndex * PART_SIZE;
        final int end = Math.min(bytes.length, start + PART_SIZE);
        final byte[] part = Arrays.copyOfRange(bytes, start, end);
        final int index = partIndex;
        final NativeByteBuffer buffer = buffer(part);
        if (buffer == null) {
            finish(null, "The selected video could not be read.");
            return;
        }
        final TLRPC.TL_upload_saveBigFilePart request = new TLRPC.TL_upload_saveBigFilePart();
        request.file_id = fileId;
        request.file_part = index;
        request.file_total_parts = partCount;
        request.bytes = buffer;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (cancelled) return;
            if (error != null) {
                finish(null, ReelsError.fromTl(error).describe());
                return;
            }
            partIndex = index + 1;
            uploadNextPart();
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private void publish() {
        if (cancelled) return;
        final TLRPC.TL_inputFile inputFile = new TLRPC.TL_inputFile();
        inputFile.id = fileId;
        inputFile.parts = partCount;
        inputFile.name = "reel.mp4";
        inputFile.md5_checksum = "";

        final TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
        video.duration = duration;
        video.w = width;
        video.h = height;
        video.supports_streaming = true;

        final TLRPC.TL_inputMediaUploadedDocument media = new TLRPC.TL_inputMediaUploadedDocument();
        media.file = inputFile;
        media.mime_type = mime;
        media.attributes = new ArrayList<>();
        media.attributes.add(video);

        final TL_reels.create request = new TL_reels.create();
        request.media = media;
        request.caption = caption;
        request.cover_time_ms = coverTimeMs;
        request.comments_enabled = true;

        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (cancelled) return;
            final ReelsError failure = ReelsError.fromTl(error);
            if (failure != null) {
                finish(null, failure.describe());
                return;
            }
            if (!(response instanceof TL_reels.Reel)) {
                finish(null, "The Reel could not be published.");
                return;
            }
            final TL_reels.Reel reel = (TL_reels.Reel) response;
            final TLRPC.User owner = MessagesController.getInstance(account).getUser(reel.owner_id);
            finish(ReelsItem.fromTl(account, reel, owner), null);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void cancel() {
        cancelled = true;
        running = false;
    }

    private void finish(ReelsItem item, String error) {
        running = false;
        if (callback != null) {
            final Callback result = callback;
            callback = null;
            result.onUploaded(item, error);
        }
    }

    private static NativeByteBuffer buffer(byte[] part) {
        try {
            final NativeByteBuffer buffer = new NativeByteBuffer(part.length);
            buffer.writeBytes(part);
            buffer.rewind();
            return buffer;
        } catch (Exception error) {
            return null;
        }
    }

    private byte[] readBytes(Uri uri) throws Exception {
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) return null;
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (Exception ignore) {
            return 0;
        }
    }
}
