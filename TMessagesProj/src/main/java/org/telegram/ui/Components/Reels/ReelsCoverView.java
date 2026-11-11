package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.URL;

/** Public Reel cover. Never resolves a document through the Telegram file loader. */
public final class ReelsCoverView extends FrameLayout {

    private final ImageView imageView;
    private final TextView initialView;
    private String currentUrl;

    public ReelsCoverView(Context context) {
        super(context);
        setBackgroundColor(0xFF111111);
        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(imageView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        initialView = new TextView(context);
        initialView.setTextColor(0xCCFFFFFF);
        initialView.setTextSize(26);
        initialView.setGravity(Gravity.CENTER);
        addView(initialView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
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
