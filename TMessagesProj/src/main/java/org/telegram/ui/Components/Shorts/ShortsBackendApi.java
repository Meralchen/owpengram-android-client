package org.telegram.ui.Components.Shorts;

import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/** REST adapter for profile-owned Reels. The API host follows the account's configured public link domain. */
public final class ShortsBackendApi {

    public interface Callback {
        void onResult(String body, int statusCode);
    }

    public interface AuthorizationCallback {
        void onResult(String header);
    }

    private static final class CachedAuthorization {
        final String header;
        final long expiresAt;
        CachedAuthorization(String header, long expiresAt) { this.header = header; this.expiresAt = expiresAt; }
    }

    private static final ConcurrentHashMap<Integer, CachedAuthorization> authorizations = new ConcurrentHashMap<>();

    private ShortsBackendApi() { }

    public static String baseUrl(int account) {
        String host = MessagesController.getInstance(account).linkPrefix;
        if (TextUtils.isEmpty(host)) return null;
        host = host.trim();
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        if (!host.startsWith("https://") && !host.startsWith("http://")) host = "https://" + host;
        return host;
    }

    public static String absoluteUrl(int account, String pathOrUrl) {
        if (TextUtils.isEmpty(pathOrUrl)) return null;
        if (pathOrUrl.startsWith("https://") || pathOrUrl.startsWith("http://")) return pathOrUrl;
        String base = baseUrl(account);
        if (base == null) return null;
        return base + (pathOrUrl.startsWith("/") ? pathOrUrl : "/" + pathOrUrl);
    }

    /** Returns a short-lived Telegram REST proof; the raw exported bytes are never persisted. */
    public static void authorizationHeader(int account, AuthorizationCallback callback) {
        CachedAuthorization cached = authorizations.get(account);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            callback.onResult(cached.header);
            return;
        }
        final TLRPC.TL_auth_exportAuthorization request = new TLRPC.TL_auth_exportAuthorization();
        request.dc_id = ConnectionsManager.getInstance(account).getCurrentDatacenterId();
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            String header = null;
            if (error == null && response instanceof TLRPC.TL_auth_exportedAuthorization) {
                TLRPC.TL_auth_exportedAuthorization exported = (TLRPC.TL_auth_exportedAuthorization) response;
                if (exported.bytes != null && exported.bytes.length > 0) {
                    String encoded = Base64.encodeToString(exported.bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                    header = "Telegram " + exported.id + ":" + encoded;
                    // Keep a safety margin below the server's five-minute expiry.
                    authorizations.put(account, new CachedAuthorization(header, System.currentTimeMillis() + 4 * 60 * 1000));
                }
            }
            final String result = header;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
        });
    }

    public static void invalidateAuthorization(int account) {
        authorizations.remove(account);
    }

    public static void request(int account, String method, String path, JSONObject json, String bearerTicket, boolean withTelegramProof, Callback callback) {
        if (withTelegramProof) {
            authorizationHeader(account, header -> {
                if (header == null) {
                    callback.onResult(null, 0);
                    return;
                }
                requestNow(account, method, path, json, bearerTicket, header, callback);
            });
        } else {
            requestNow(account, method, path, json, bearerTicket, null, callback);
        }
    }

    public static void uploadPart(int account, String uploadId, int partNumber, byte[] bytes, String ticket, Callback callback) {
        final String url = absoluteUrl(account, "/api/profiles/me/reels/uploads/" + android.net.Uri.encode(uploadId) + "/parts/" + partNumber);
        if (url == null || bytes == null) {
            callback.onResult(null, 0);
            return;
        }
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            String body = null;
            int status = 0;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("PUT");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-Type", "application/octet-stream");
                connection.setRequestProperty("Authorization", "Bearer " + ticket);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
                status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
                if (stream != null) {
                    StringBuilder result = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) result.append(line);
                    }
                    body = result.toString();
                }
            } catch (Exception ignore) {
                // Never log a ticket or authorization material.
            } finally {
                if (connection != null) connection.disconnect();
            }
            final String responseBody = body;
            final int responseCode = status;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(responseBody, responseCode));
        });
    }

    private static void requestNow(int account, String method, String path, JSONObject json, String bearerTicket, String telegramAuthorization, Callback callback) {
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
                if (telegramAuthorization != null) connection.setRequestProperty("Authorization", telegramAuthorization);
                else if (!TextUtils.isEmpty(bearerTicket)) connection.setRequestProperty("Authorization", "Bearer " + bearerTicket);
                if (json != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(json.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
                status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
                if (stream != null) {
                    StringBuilder result = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) result.append(line);
                    }
                    body = result.toString();
                }
            } catch (Exception ignore) {
                // Return an empty failure to the feature layer; never log auth headers or tickets.
            } finally {
                if (connection != null) connection.disconnect();
            }
            final String responseBody = body;
            final int responseCode = status;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(responseBody, responseCode));
        });
    }
}
