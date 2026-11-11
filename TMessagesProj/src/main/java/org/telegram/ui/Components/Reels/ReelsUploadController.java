package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;

/** A real foreground profile Reel upload: session, 512 KiB parts, then completion. */
public final class ReelsUploadController {

    public interface Callback {
        void onUploaded(ReelsItem item, String error);
    }

    private static final int PART_SIZE = 512 * 1024;

    private final int account;
    private final Context context;
    private boolean running;
    private boolean cancelled;
    private String idempotencyKey;
    private String uploadId;
    private String ticket;
    private byte[] bytes;
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
        this.idempotencyKey = UUID.randomUUID().toString();
        this.uploadId = null;
        this.ticket = null;
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
                    final int width = parseInt(retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    final int height = parseInt(retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    retriever.release();
                    return new Object[]{data, durationMs, width, height};
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
                final int width = (int) result[2];
                final int height = (int) result[3];
                final String mime = context.getContentResolver().getType(uri);
                createSession(TextUtils.isEmpty(caption) ? "" : caption,
                        Math.max(0, coverTimeMs),
                        Math.max(1, (int) Math.ceil(durationMs / 1000d)),
                        width, height,
                        TextUtils.isEmpty(mime) ? "video/mp4" : mime);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void createSession(String caption, int coverTimeMs, int duration, int width, int height, String mime) {
        final JSONObject body = new JSONObject();
        try {
            body.put("idempotencyKey", idempotencyKey);
            body.put("sizeBytes", bytes.length);
            body.put("mimeType", mime);
            body.put("duration", duration);
            body.put("width", width);
            body.put("height", height);
            body.put("coverTimeMs", coverTimeMs);
            body.put("caption", caption);
        } catch (Exception ignore) {
        }
        ReelsBackendApi.request(account, "POST", "/api/profiles/me/reels/uploads",
                body, null, true, (response, status) -> {
                    if (cancelled) return;
                    if (status < 200 || status >= 300) {
                        finish(null, ReelsError.fromResponse(status, response).describe());
                        return;
                    }
                    try {
                        final JSONObject root = new JSONObject(response == null ? "{}" : response);
                        final JSONObject session = root.optJSONObject("upload") != null
                                ? root.optJSONObject("upload") : root;
                        uploadId = session.optString("uploadId", session.optString("id", null));
                        ticket = session.optString("ticket", session.optString("uploadTicket", null));
                        if (TextUtils.isEmpty(uploadId) || TextUtils.isEmpty(ticket)) {
                            finish(null, "The upload session could not be started.");
                            return;
                        }
                        partCount = Math.max(1, (bytes.length + PART_SIZE - 1) / PART_SIZE);
                        partIndex = 0;
                        uploadNextPart();
                    } catch (Exception malformed) {
                        finish(null, "The upload session could not be read.");
                    }
                });
    }

    private void uploadNextPart() {
        if (cancelled) return;
        if (partIndex >= partCount) {
            completeUpload();
            return;
        }
        final int start = partIndex * PART_SIZE;
        final int end = Math.min(bytes.length, start + PART_SIZE);
        final byte[] part = Arrays.copyOfRange(bytes, start, end);
        final int number = partIndex + 1;
        uploadPartWithRetry(number, part, 0);
    }

    private void uploadPartWithRetry(int partNumber, byte[] part, int attempt) {
        ReelsBackendApi.uploadPart(account, uploadId, partNumber, part, ticket, (response, status) -> {
            if (cancelled) return;
            if (status >= 200 && status < 300) {
                partIndex++;
                uploadNextPart();
                return;
            }
            if (attempt == 0 && (status == 0 || status >= 500)) {
                uploadPartWithRetry(partNumber, part, attempt + 1);
                return;
            }
            finish(null, ReelsError.fromResponse(status, response).describe());
        });
    }

    private void completeUpload() {
        ReelsBackendApi.request(account, "POST",
                "/api/profiles/me/reels/uploads/" + Uri.encode(uploadId) + "/complete",
                new JSONObject(), ticket, false, (response, status) -> {
                    if (cancelled) return;
                    if (status < 200 || status >= 300) {
                        finish(null, ReelsError.fromResponse(status, response).describe());
                        return;
                    }
                    ReelsItem item = null;
                    try {
                        final JSONObject root = new JSONObject(response == null ? "{}" : response);
                        final JSONObject object = root.optJSONObject("item") != null
                                ? root.optJSONObject("item") : root.optJSONObject("reel");
                        item = ReelsItem.fromJson(account, object);
                    } catch (Exception ignore) {
                    }
                    finish(item, null);
                });
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
