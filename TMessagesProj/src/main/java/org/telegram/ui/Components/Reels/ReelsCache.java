package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.text.TextUtils;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;

import org.telegram.messenger.FileStreamLoadOperation;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Reel media plumbing.
 *
 * <p>A Reel's video is a regular Telegram document, so playback goes through
 * {@link FileStreamLoadOperation} over the account's MTProto connection and
 * never through HTTP. {@link FileStreamLoadOperation} reconstructs the document
 * from the URI query, so a Reel simply encodes its document there and the
 * player stack stays untouched.
 */
public final class ReelsCache {

    /** URI scheme that routes playback through the MTProto file loader. */
    public static final String SCHEME = "reel";

    private ReelsCache() {
    }

    public static String streamUri(int account, TLRPC.Document document) {
        if (document == null || document.id == 0 || document.size <= 0) {
            return null;
        }
        final StringBuilder builder = new StringBuilder(SCHEME).append("://video?");
        builder.append("account=").append(account);
        builder.append("&rid=0");
        builder.append("&id=").append(document.id);
        builder.append("&hash=").append(document.access_hash);
        builder.append("&size=").append(document.size);
        builder.append("&dc=").append(document.dc_id);
        builder.append("&mime=").append(UriEncode(TextUtils.isEmpty(document.mime_type) ? "video/mp4" : document.mime_type));
        builder.append("&name=reel.mp4");
        builder.append("&reference=").append(hex(document.file_reference));
        return builder.toString();
    }

    /** Public share landing page served by the same server, or null when unknown. */
    public static String shareUrl(int account, String reelId) {
        if (TextUtils.isEmpty(reelId)) {
            return null;
        }
        final MessagesController controller = MessagesController.getInstance(account);
        String base = controller.publicBaseUrl;
        if (TextUtils.isEmpty(base)) {
            base = controller.linkPrefix;
            if (!TextUtils.isEmpty(base) && !base.startsWith("http://") && !base.startsWith("https://")) {
                base = "https://" + base;
            }
        }
        if (TextUtils.isEmpty(base)) {
            return null;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/r/" + reelId;
    }

    public static DataSource.Factory dataSourceFactory(Context context) {
        return () -> new ReelDataSource(context);
    }

    public static void clearAccount(int account) {
        // Documents and their file references are owned by FileLoader; nothing
        // Reels-specific is cached here.
    }

    private static String UriEncode(String value) {
        return value.replace("/", "%2F").replace(" ", "%20");
    }

    private static String hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        final StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format(Locale.US, "%02x", b));
        }
        return out.toString();
    }

    private static final class ReelDataSource implements DataSource {
        private final DefaultHttpDataSource http;
        private FileStreamLoadOperation stream;
        private final ArrayList<TransferListener> listeners = new ArrayList<>();
        private TransferListener listener;

        ReelDataSource(Context context) {
            http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("OWPengram-Reels")
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(30000)
                    .setAllowCrossProtocolRedirects(true)
                    .createDataSource();
        }

        private boolean isReel(DataSpec dataSpec) {
            return dataSpec != null && dataSpec.uri != null && SCHEME.equals(dataSpec.uri.getScheme());
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            if (isReel(dataSpec)) {
                stream = new FileStreamLoadOperation();
                if (listener != null) {
                    stream.addTransferListener(listener);
                }
                return stream.open(dataSpec);
            }
            return http.open(dataSpec);
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) throws IOException {
            return stream != null ? stream.read(buffer, offset, readLength) : http.read(buffer, offset, readLength);
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            } else {
                http.close();
            }
        }

        @Override
        public android.net.Uri getUri() {
            return stream != null ? stream.getUri() : http.getUri();
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            listeners.add(transferListener);
            listener = transferListener;
            http.addTransferListener(transferListener);
        }

    }
}
