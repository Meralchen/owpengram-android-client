package org.telegram.ui.Components.Reels;

import android.net.Uri;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP adapter for the profile-only Reels REST contract. The Telegram proof is used only for
 * personalized reads and mutations; media itself is always a public HTTP URL.
 */
public final class ReelsBackendApi {

    public interface Callback {
        void onResult(String body, int statusCode);
    }

    public interface AuthorizationCallback {
        void onResult(String header);
    }

    private static final class CachedAuthorization {
        final String header;
        final long expiresAt;

        CachedAuthorization(String header, long expiresAt) {
            this.header = header;
            this.expiresAt = expiresAt;
        }
    }

    private static final ConcurrentHashMap<Integer, CachedAuthorization> authorizations = new ConcurrentHashMap<>();

    private ReelsBackendApi() {
    }

    public static String baseUrl(int account) {
        final MessagesController controller = MessagesController.getInstance(account);
        return resolveBaseUrl(controller.publicBaseUrl, controller.linkPrefix);
    }

    static String resolveBaseUrl(String publicBaseUrl, String linkPrefix) {
        String base = normalizeBaseUrl(publicBaseUrl);
        if (base != null) {
            return base;
        }
        base = normalizeBaseUrl(linkPrefix);
        if (base == null) {
            return null;
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        return base;
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null) {
            return null;
        }
        value = value.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }

    private static void logRequestFailure(String method, String url, Exception error) {
        String scheme = "unknown";
        String host = "unknown";
        try {
            final URL parsed = new URL(url);
            scheme = parsed.getProtocol();
            host = parsed.getHost();
            if (parsed.getPort() >= 0) {
                host += ":" + parsed.getPort();
            }
        } catch (Exception ignored) {
        }
        FileLog.w("Reels HTTP " + method + " " + scheme + "://" + host
                + " failed: " + error.getClass().getName());
    }

    public static String absoluteUrl(int account, String pathOrUrl) {
        if (TextUtils.isEmpty(pathOrUrl)) {
            return null;
        }
        if (pathOrUrl.startsWith("https://") || pathOrUrl.startsWith("http://")) {
            return pathOrUrl;
        }
        final String base = baseUrl(account);
        if (base == null) {
            return null;
        }
        return base + (pathOrUrl.startsWith("/") ? pathOrUrl : "/" + pathOrUrl);
    }

    /** Returns a short-lived Telegram REST proof; the exported bytes are never persisted. */
    public static void authorizationHeader(int account, AuthorizationCallback callback) {
        final CachedAuthorization cached = authorizations.get(account);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            callback.onResult(cached.header);
            return;
        }
        final TLRPC.TL_auth_exportAuthorization request = new TLRPC.TL_auth_exportAuthorization();
        request.dc_id = ConnectionsManager.getInstance(account).getCurrentDatacenterId();
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            String header = null;
            if (error == null && response instanceof TLRPC.TL_auth_exportedAuthorization) {
                final TLRPC.TL_auth_exportedAuthorization exported = (TLRPC.TL_auth_exportedAuthorization) response;
                if (exported.bytes != null && exported.bytes.length > 0) {
                    final String encoded = Base64.encodeToString(exported.bytes,
                            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                    header = "Telegram " + exported.id + ":" + encoded;
                    authorizations.put(account, new CachedAuthorization(header, System.currentTimeMillis() + 4 * 60 * 1000L));
                }
            }
            final String result = header;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
        });
    }

    public static void invalidateAuthorization(int account) {
        authorizations.remove(account);
    }

    public static void request(int account, String method, String path, JSONObject json, String bearerTicket,
                               boolean withTelegramProof, Callback callback) {
        request(account, method, path, json, bearerTicket, withTelegramProof, false, callback);
    }

    private static void request(int account, String method, String path, JSONObject json, String bearerTicket,
                                boolean withTelegramProof, boolean retriedAfter401, Callback callback) {
        final boolean bearer = !TextUtils.isEmpty(bearerTicket);
        if (!withTelegramProof || bearer) {
            requestNow(account, method, path, json, bearerTicket, null, callback);
            return;
        }
        authorizationHeader(account, header -> {
            if (header == null) {
                callback.onResult(null, 0);
                return;
            }
            requestNow(account, method, path, json, null, header, (body, status) -> {
                if (status == 401 && !retriedAfter401) {
                    invalidateAuthorization(account);
                    request(account, method, path, json, null, true, true, callback);
                } else {
                    callback.onResult(body, status);
                }
            });
        });
    }

    public static void uploadPart(int account, String uploadId, int partNumber, byte[] bytes,
                                  String ticket, Callback callback) {
        final String path = "/api/profiles/me/reels/uploads/" + Uri.encode(uploadId)
                + "/parts/" + partNumber;
        uploadRaw(account, path, "application/octet-stream", bytes, ticket, false, callback);
    }

    /** Uploads raw image/gif bytes for a comment using the same Telegram proof contract. */
    public static void uploadCommentGif(int account, byte[] gif, Callback callback) {
        if (gif == null || gif.length == 0) {
            callback.onResult(null, 0);
            return;
        }
        uploadRaw(account, "/api/comment-gifs", "image/gif", gif, null, true, callback);
    }

    private static void uploadRaw(int account, String path, String contentType, byte[] bytes,
                                  String bearerTicket, boolean withTelegramProof, Callback callback) {
        uploadRaw(account, path, contentType, bytes, bearerTicket, withTelegramProof, false, callback);
    }

    private static void uploadRaw(int account, String path, String contentType, byte[] bytes,
                                  String bearerTicket, boolean withTelegramProof, boolean retriedAfter401,
                                  Callback callback) {
        final String url = absoluteUrl(account, path);
        if (url == null || bytes == null) {
            callback.onResult(null, 0);
            return;
        }
        final boolean bearer = !TextUtils.isEmpty(bearerTicket);
        if (!withTelegramProof || bearer) {
            executeRawUpload(url, contentType, bytes, bearerTicket, null, callback);
            return;
        }
        authorizationHeader(account, header -> {
            if (header == null) {
                callback.onResult(null, 0);
                return;
            }
            executeRawUpload(url, contentType, bytes, null, header, (body, status) -> {
                if (status == 401 && !retriedAfter401) {
                    invalidateAuthorization(account);
                    uploadRaw(account, path, contentType, bytes, null, true, true, callback);
                } else {
                    callback.onResult(body, status);
                }
            });
        });
    }

    private static void executeRawUpload(String url, String contentType, byte[] bytes, String bearerTicket,
                                         String telegramHeader, Callback callback) {
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            String body = null;
            int status = 0;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(90000);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-Type", contentType);
                if (!TextUtils.isEmpty(bearerTicket)) {
                    connection.setRequestProperty("Authorization", "Bearer " + bearerTicket);
                } else if (!TextUtils.isEmpty(telegramHeader)) {
                    connection.setRequestProperty("Authorization", telegramHeader);
                }
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
                status = connection.getResponseCode();
                final InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                if (stream != null) {
                    final StringBuilder result = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            result.append(line);
                        }
                    }
                    body = result.toString();
                }
            } catch (Exception error) {
                logRequestFailure("POST", url, error);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            final String responseBody = body;
            final int responseCode = status;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(responseBody, responseCode));
        });
    }

    private static void requestNow(int account, String method, String path, JSONObject json,
                                   String bearerTicket, String telegramHeader, Callback callback) {
        final String url = absoluteUrl(account, path);
        if (url == null) {
            callback.onResult(null, 0);
            return;
        }
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            String body = null;
            int status = 0;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("Accept", "application/json");
                if (!TextUtils.isEmpty(bearerTicket)) {
                    connection.setRequestProperty("Authorization", "Bearer " + bearerTicket);
                } else if (!TextUtils.isEmpty(telegramHeader)) {
                    connection.setRequestProperty("Authorization", telegramHeader);
                }
                if (json != null && !"GET".equals(method)) {
                    final byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(payload.length);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(payload);
                    }
                }
                status = connection.getResponseCode();
                final InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                if (stream != null) {
                    final StringBuilder result = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            result.append(line);
                        }
                    }
                    body = result.toString();
                }
            } catch (Exception error) {
                logRequestFailure(method, url, error);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            final String responseBody = body;
            final int responseCode = status;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(responseBody, responseCode));
        });
    }
}
