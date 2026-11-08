package org.telegram.ui.Components.Shorts;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Uploads a profile Reel through resumable REST parts; the short-lived ticket stays in memory. */
public final class ShortsProfileReelUploader {

    public interface Callback {
        void onComplete();
    }

    private static final int PART_SIZE = 512 * 1024;
    private static final long MAX_SIZE = 500L * 1024 * 1024;
    private static final long MAX_DURATION_SECONDS = 600;
    private static final Executor IO = Executors.newSingleThreadExecutor();

    private final Context context;
    private final int account;
    private final Uri uri;
    private final Callback callback;
    private final String idempotencyKey = UUID.randomUUID().toString();
    private long sizeBytes;
    private double duration;
    private int width;
    private int height;
    private String mimeType;
    private String caption = "";
    private String uploadId;
    private String ticket;
    private InputStream input;
    private byte[] currentPart;
    private int partNumber;
    private long uploadedBytes;
    private boolean cancelled;
    private TextView statusView;
    private ProgressBar progressBar;
    private AlertDialog progressDialog;
    private Runnable retryAction;

    public ShortsProfileReelUploader(Context context, int account, Uri uri, Callback callback) {
        this.context = context;
        this.account = account;
        this.uri = uri;
        this.callback = callback;
    }

    public void start() {
        IO.execute(() -> {
            try {
                readMetadata();
                AndroidUtilities.runOnUIThread(this::showCaptionEditor);
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() ->
                        new AlertDialog.Builder(context)
                                .setMessage(LocaleController.getString(R.string.ShortsUploadInvalid))
                                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                                .show());
            }
        });
    }

    private void readMetadata() throws Exception {
        ContentResolver resolver = context.getContentResolver();
        mimeType = resolver.getType(uri);
        if (TextUtils.isEmpty(mimeType)) throw new IllegalArgumentException("Unknown MIME type");
        mimeType = mimeType.toLowerCase(java.util.Locale.US);
        if (!mimeType.equals("video/mp4") && !mimeType.equals("video/quicktime") && !mimeType.equals("video/webm")) {
            throw new IllegalArgumentException("Unsupported video type");
        }
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) sizeBytes = cursor.getLong(0);
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_SIZE) throw new IllegalArgumentException("Invalid video size");

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String durationValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String widthValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (TextUtils.isEmpty(durationValue) || TextUtils.isEmpty(widthValue) || TextUtils.isEmpty(heightValue)) {
                throw new IllegalArgumentException("Missing video metadata");
            }
            duration = Long.parseLong(durationValue) / 1000.0;
            width = Integer.parseInt(widthValue);
            height = Integer.parseInt(heightValue);
        } finally {
            retriever.release();
        }
        if (duration <= 0 || duration > MAX_DURATION_SECONDS || width <= 0 || height <= 0 || width > 8192 || height > 8192) {
            throw new IllegalArgumentException("Video metadata out of range");
        }
    }

    private void showCaptionEditor() {
        if (cancelled) return;
        EditText captionInput = new EditText(context);
        captionInput.setHint(LocaleController.getString(R.string.ShortsUploadCaption));
        captionInput.setMaxLines(5);
        int padding = AndroidUtilities.dp(20);
        captionInput.setPadding(padding, padding, padding, padding);
        new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.ShortsUploadReel))
                .setView(captionInput)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Continue), (dialog, which) -> {
                    caption = captionInput.getText() != null ? captionInput.getText().toString().trim() : "";
                    showProgress();
                    createSession();
                })
                .show();
    }

    private void showProgress() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = AndroidUtilities.dp(20);
        layout.setPadding(padding, padding, padding, padding);
        statusView = new TextView(context);
        statusView.setText(LocaleController.getString(R.string.ShortsUploadPreparing));
        layout.addView(statusView);
        progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        layout.addView(progressBar, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(4)));
        progressDialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.ShortsUploadReel))
                .setView(layout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> cancel())
                .setNeutralButton(LocaleController.getString(R.string.Retry), null)
                .create();
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.setOnShowListener(dialog -> {
            progressDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(android.view.View.GONE);
        });
        progressDialog.show();
    }

    private void createSession() {
        retryAction = this::createSession;
        JSONObject body = new JSONObject();
        try {
            body.put("idempotencyKey", idempotencyKey);
            body.put("sizeBytes", sizeBytes);
            body.put("mimeType", mimeType);
            body.put("duration", duration);
            body.put("width", width);
            body.put("height", height);
            body.put("caption", caption);
        } catch (JSONException e) {
            fail();
            return;
        }
        ShortsBackendApi.request(account, "POST", "/api/profiles/me/reels/uploads", body, null, true, (response, status) -> {
            if (cancelled) return;
            if (status < 200 || status >= 300 || response == null) {
                fail();
                return;
            }
            try {
                JSONObject result = new JSONObject(response);
                uploadId = result.getString("uploadId");
                ticket = result.getString("ticket");
                if (TextUtils.isEmpty(uploadId) || TextUtils.isEmpty(ticket) || result.optInt("partSize", PART_SIZE) != PART_SIZE) {
                    fail();
                    return;
                }
                openStreamAndUpload();
            } catch (JSONException e) {
                fail();
            }
        });
    }

    private void openStreamAndUpload() {
        retryAction = this::openStreamAndUpload;
        IO.execute(() -> {
            try {
                if (input != null) input.close();
                input = context.getContentResolver().openInputStream(uri);
                if (input == null) throw new IllegalStateException("Could not read video");
                partNumber = 0;
                uploadedBytes = 0;
                currentPart = null;
                AndroidUtilities.runOnUIThread(this::readNextPart);
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(this::fail);
            }
        });
    }

    private void readNextPart() {
        if (cancelled) return;
        if (uploadedBytes >= sizeBytes) {
            completeUpload();
            return;
        }
        final int expected = (int) Math.min(PART_SIZE, sizeBytes - uploadedBytes);
        IO.execute(() -> {
            try {
                byte[] part = new byte[expected];
                int offset = 0;
                while (offset < expected) {
                    int count = input.read(part, offset, expected - offset);
                    if (count < 0) throw new IllegalStateException("Video ended before the declared size");
                    offset += count;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (cancelled) return;
                    currentPart = part;
                    uploadCurrentPart();
                });
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(this::fail);
            }
        });
    }

    private void uploadCurrentPart() {
        if (cancelled || currentPart == null) return;
        retryAction = this::uploadCurrentPart;
        ShortsBackendApi.uploadPart(account, uploadId, partNumber, currentPart, ticket, (response, status) -> {
            if (cancelled) return;
            if (status < 200 || status >= 300) {
                fail();
                return;
            }
            uploadedBytes += currentPart.length;
            partNumber++;
            currentPart = null;
            progressBar.setProgress((int) Math.min(100, uploadedBytes * 100 / sizeBytes));
            statusView.setText(LocaleController.formatString(R.string.ShortsUploadProgress, (int) (uploadedBytes * 100 / sizeBytes)));
            readNextPart();
        });
    }

    private void completeUpload() {
        retryAction = this::completeUpload;
        statusView.setText(LocaleController.getString(R.string.ShortsUploadFinishing));
        ShortsBackendApi.request(account, "POST", "/api/profiles/me/reels/uploads/" + Uri.encode(uploadId) + "/complete",
                null, ticket, false, (response, status) -> {
                    if (cancelled) return;
                    if (status < 200 || status >= 300 || response == null) {
                        fail();
                        return;
                    }
                    try {
                        if (TextUtils.isEmpty(new JSONObject(response).optString("reelId", ""))) {
                            fail();
                            return;
                        }
                        if (input != null) input.close();
                        if (progressDialog != null) progressDialog.dismiss();
                        callback.onComplete();
                    } catch (Exception e) {
                        fail();
                    }
                });
    }

    private void fail() {
        if (cancelled || progressDialog == null) return;
        statusView.setText(LocaleController.getString(R.string.ShortsUploadFailed));
        android.view.View retry = progressDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        retry.setVisibility(android.view.View.VISIBLE);
        ((TextView) retry).setOnClickListener(v -> {
            retry.setVisibility(android.view.View.GONE);
            retryAction.run();
        });
    }

    private void cancel() {
        cancelled = true;
        if (input != null) {
            IO.execute(() -> {
                try {
                    input.close();
                } catch (Exception ignore) {
                }
            });
        }
        if (!TextUtils.isEmpty(uploadId) && !TextUtils.isEmpty(ticket)) {
            ShortsBackendApi.request(account, "DELETE", "/api/profiles/me/reels/uploads/" + Uri.encode(uploadId), null, ticket, false, (body, status) -> { });
        }
        progressDialog = null;
        currentPart = null;
    }
}
