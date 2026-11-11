package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.ImageLocation;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BackupImageView;

import java.io.InputStream;
import java.net.URL;

/** Reel cover. MTProto Reels resolve their document thumbnail through ImageLoader. */
public final class ReelsCoverView extends FrameLayout {

    private final BackupImageView imageView;
    private final TextView initialView;
    private String currentUrl;

    public ReelsCoverView(Context context) {
        super(context);
        setBackgroundColor(0xFF111111);
        imageView = new BackupImageView(context);
        imageView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        addView(imageView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        initialView = new TextView(context);
        initialView.setTextColor(0xCCFFFFFF);
        initialView.setTextSize(26);
        initialView.setGravity(Gravity.CENTER);
        addView(initialView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** Document-backed cover: the poster is one of the video document's thumbs. */
    public void setCover(TLRPC.Document document, TLRPC.PhotoSize thumb, String initial) {
        currentUrl = document == null ? null : "doc:" + document.id;
        imageView.setImageDrawable(null);
        initialView.setText(TextUtils.isEmpty(initial) ? "" : initial);
        initialView.setVisibility(VISIBLE);
        if (document == null || thumb == null) {
            return;
        }
        imageView.setImage(ImageLocation.getForDocument(thumb, document), null, (Drawable) null, document);
        initialView.setVisibility(GONE);
    }

    public void setCover(String url, String initial) {
        currentUrl = url;
        imageView.setImageDrawable(null);
        initialView.setText(TextUtils.isEmpty(initial) ? "" : initial);
        initialView.setVisibility(VISIBLE);
        if (TextUtils.isEmpty(url)) return;
        final String requested = url;
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... voids) {
                try (InputStream stream = new URL(requested).openStream()) {
                    final BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    return BitmapFactory.decodeStream(stream, null, options);
                } catch (Exception ignore) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap != null && requested.equals(currentUrl)) {
                    imageView.setImageBitmap(bitmap);
                    initialView.setVisibility(GONE);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void showImage() {
        initialView.setVisibility(GONE);
    }

    public void setImageColor(int color) {
        setBackgroundColor(color);
        initialView.setTextColor(Color.WHITE);
    }
}
