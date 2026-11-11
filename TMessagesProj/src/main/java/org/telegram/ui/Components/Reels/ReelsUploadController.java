package org.telegram.ui.Components.Reels;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.PorterDuff;
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
public final class ReelsUploadController {

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

    public ReelsUploadController(Context context, int account, Uri uri, Callback callback) {
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
            if (durationValue != null) duration = Math.max(0.1, Double.parseDouble(durationValue) / 1000.0);
            if (widthValue != null) width = Integer.parseInt(widthValue);
            if (heightValue != null) height = Integer.parseInt(heightValue);
        } finally {
            try { retriever.release(); } catch (Exception ignore) {}
        }
        if (duration <= 0 || duration > MAX_DURATION_SECONDS) throw new IllegalArgumentException("Video exceeds duration limit");
    }

    private void showCaptionEditor() {
        final EditText input = new EditText(context);
        input.setHint(LocaleController.getString(R.string.ShortsUploadCaption));
        new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.ShortsUploadReel))
                .setView(input)
                .setPositiveButton(LocaleController.getString(R.string.Send), (dialog, which) -> {
                    caption = input.getText() != null ? input.getText().toString().trim() : "";
                    startUpload();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void startUpload() {
        showProgress();
        requestSession();
    }

    private void showProgress() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.ShortsUploadReel));
        statusView = new TextView(context);
        statusView.setTextColor(ReelsTheme.textPrimary(null));
        statusView.setText(LocaleController.getString(R.string.ShortsUploadPreparing));
        statusView.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), 0);
        progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        if (progressBar.getProgressDrawable() != null) {
            progressBar.getProgressDrawable().setColorFilter(ReelsTheme.accent(null), PorterDuff.Mode.SRC_IN);
        }
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(16));
        android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.addView(statusView);
        layout.addView(progressBar);
        builder.setView(layout);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> cancelUpload());
        progressDialog = builder.create();
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();
    }

    private void requestSession() {
        JSONObject body = new JSONObject();
        try {
            body.put("idempotencyKey", idempotencyKey);
            body.put("sizeBytes", sizeBytes);
            body.put("mimeType", mimeType);
            body.put("duration", duration);
            if (width > 0) body.put("width", width);
            if (height > 0) body.put("height", height);
            if (!TextUtils.isEmpty(caption)) body.put("caption", caption);
        } catch (JSONException e) {
            fail(this::requestSession);
            return;
        }
        ReelsBackendApi.request(account, "POST", "/api/profiles/me/reels/uploads", body, null, true, (response, status) -> {
            if (cancelled) return;
            if (status >= 200 && status < 300 && response != null) {
                try {
                    JSONObject result = new JSONObject(response);
                    uploadId = result.optString("uploadId", result.optString("id", null));
                    ticket = result.optString("ticket", null);
                    if (uploadId != null && ticket != null) {
                        IO.execute(this::beginParts);
                        return;
                    }
                } catch (JSONException ignore) {}
            }
            fail(this::requestSession);
        });
    }

    private void beginParts() {
        try {
            input = context.getContentResolver().openInputStream(uri);
            partNumber = 0;
            uploadedBytes = 0;
            readNextPart();
        } catch (Exception e) {
            AndroidUtilities.runOnUIThread(() -> fail(this::beginParts));
        }
    }

    private void readNextPart() {
        if (cancelled) {
            closeStream();
            return;
        }
        try {
            byte[] buffer = new byte[PART_SIZE];
            int totalRead = 0;
            while (totalRead < PART_SIZE) {
                int read = input.read(buffer, totalRead, PART_SIZE - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
            if (totalRead == 0) {
                closeStream();
                AndroidUtilities.runOnUIThread(this::completeUpload);
                return;
            }
            if (totalRead < PART_SIZE) {
                byte[] trimmed = new byte[totalRead];
                System.arraycopy(buffer, 0, trimmed, 0, totalRead);
                currentPart = trimmed;
            } else {
                currentPart = buffer;
            }
            uploadCurrentPart();
        } catch (Exception e) {
            AndroidUtilities.runOnUIThread(() -> fail(this::readNextPart));
        }
    }

    private void uploadCurrentPart() {
        if (cancelled) return;
        final int thisPart = partNumber;
        final int partLength = currentPart.length;
        ReelsBackendApi.uploadPart(account, uploadId, thisPart, currentPart, ticket, (body, status) -> {
            if (cancelled) return;
            if (status >= 200 && status < 300) {
                uploadedBytes += partLength;
                partNumber++;
                updateProgress(uploadedBytes, sizeBytes);
                IO.execute(this::readNextPart);
            } else {
                fail(this::uploadCurrentPart);
            }
        });
    }

    private void completeUpload() {
        if (cancelled) return;
        if (statusView != null) statusView.setText(LocaleController.getString(R.string.ShortsUploadFinishing));
        ReelsBackendApi.request(account, "POST", "/api/profiles/me/reels/uploads/" + android.net.Uri.encode(uploadId) + "/complete", null, ticket, false, (response, status) -> {
            if (cancelled) return;
            if (status >= 200 && status < 300) {
                dismissProgress();
                if (callback != null) callback.onComplete();
            } else {
                fail(this::completeUpload);
            }
        });
    }

    private void updateProgress(long current, long total) {
        if (progressBar == null || statusView == null) return;
        int percent = (int) Math.min(99, Math.max(0, total > 0 ? (current * 100 / total) : 0));
        progressBar.setProgress(percent);
        statusView.setText(LocaleController.formatString(R.string.ShortsUploadProgress, percent));
    }

    private void fail(Runnable retry) {
        retryAction = retry;
        if (statusView != null) statusView.setText(LocaleController.getString(R.string.ShortsUploadFailed));
        if (progressDialog != null) {
            progressDialog.setButton(AlertDialog.BUTTON_POSITIVE, LocaleController.getString(R.string.Retry), (d, w) -> {
                if (retryAction != null) {
                    if (statusView != null) statusView.setText(LocaleController.getString(R.string.ShortsUploadPreparing));
                    Runnable action = retryAction;
                    retryAction = null;
                    action.run();
                }
            });
        }
    }

    private void cancelUpload() {
        cancelled = true;
        closeStream();
        if (uploadId != null && ticket != null) {
            ReelsBackendApi.request(account, "DELETE", "/api/profiles/me/reels/uploads/" + android.net.Uri.encode(uploadId), null, ticket, false, (b, s) -> {});
        }
        dismissProgress();
    }

    private void closeStream() {
        try { if (input != null) input.close(); } catch (Exception ignore) {}
        input = null;
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            try { progressDialog.dismiss(); } catch (Exception ignore) {}
            progressDialog = null;
        }
    }
}
