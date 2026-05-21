package org.jenkinsci.plugins.vmanager.charts.util;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.util.Secret;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single shared HTTP client for all vManager REST calls in this plugin.
 *
 * <p>Centralises the three things that were previously duplicated across
 * {@code VManagerSessionsClient}, {@code MetricDefinition.DescriptorImpl},
 * {@code ChartDefinition.DescriptorImpl} and {@code CustomMetricsRunListener}:</p>
 * <ul>
 *   <li>opening an {@link HttpURLConnection} that trusts all certs / hostnames
 *       (vManager servers commonly use self-signed certs);</li>
 *   <li>HTTP {@code GET} returning the response body as a UTF-8 string;</li>
 *   <li>HTTP {@code POST} of a JSON body returning the response body as a
 *       UTF-8 string.</li>
 * </ul>
 *
 * <p>Both verbs add HTTP Basic auth from the supplied credentials (when
 * non-null), use a {@value #TIMEOUT_MS}&nbsp;ms connect/read timeout, and
 * raise an {@link IOException} that includes the error body for any
 * HTTP&nbsp;&ge;&nbsp;400 response.</p>
 */
public final class VManagerHttpClient {

    public static final int TIMEOUT_MS = 60_000;
    public static final int READ_TIMEOUT_MS = 60 * 60 * 1000; // 1 hour

    private VManagerHttpClient() {
        // utility class
    }

    /**
     * HTTP response container: trimmed body + a case-insensitive view of the
     * response headers.
     */
    public static final class Response {
        public final String body;
        /** Lower-cased header name &rarr; raw value (first value if server sent multiple). */
        public final Map<String, String> headers;

        Response(String body, Map<String, String> headers) {
            this.body    = body;
            this.headers = headers == null ? Collections.emptyMap() : headers;
        }

        /** Case-insensitive lookup. */
        public String header(String name) {
            return name == null ? null : headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /**
     * Opens a connection that trusts all certificates and hostnames.
     * Returns an {@link HttpsURLConnection} for {@code https://} URLs,
     * a plain {@link HttpURLConnection} otherwise.
     */
    public static HttpURLConnection openConnection(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        if ("https".equalsIgnoreCase(url.getProtocol())) {
            try {
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, new TrustManager[]{new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String a) { }
                    public void checkServerTrusted(X509Certificate[] c, String a) { }
                }}, new SecureRandom());
                HttpsURLConnection https = (HttpsURLConnection) url.openConnection();
                https.setSSLSocketFactory(sc.getSocketFactory());
                https.setHostnameVerifier((h, s) -> true);
                return https;
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException("Failed to set up trust-all SSL context: "
                        + e.getMessage(), e);
            }
        }
        return (HttpURLConnection) url.openConnection();
    }

    /**
     * HTTP {@code GET}. {@code creds} may be {@code null} for unauthenticated.
     *
     * @return the response body as a trimmed UTF-8 string.
     */
    public static String getJson(String urlStr,
                                 StandardUsernamePasswordCredentials creds) throws IOException {
        HttpURLConnection conn = openConnection(urlStr);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept",       "application/json, */*;q=0.8");
        conn.setRequestProperty("Content-Type", "application/json");
        applyAuth(conn, creds);
        return readResponse(conn, urlStr);
    }

    /**
     * HTTP {@code POST} of a JSON body. {@code creds} may be {@code null}.
     *
     * @return the response body as a trimmed UTF-8 string.
     */
    public static String postJson(String urlStr, String jsonBody,
                                  StandardUsernamePasswordCredentials creds) throws IOException {
        return postJsonFull(urlStr, jsonBody, creds, null).body;
    }

    /**
     * HTTP {@code POST} of a JSON body, with optional extra request headers,
     * returning both response body and response headers.
     *
     * @param urlStr       full URL.
     * @param jsonBody     JSON request body.
     * @param creds        HTTP Basic credentials, or {@code null}.
     * @param extraHeaders extra request headers to set (e.g.
     *                     {@code x-vmgr-routing-retain}); may be {@code null}.
     */
    public static Response postJsonFull(String urlStr, String jsonBody,
                                        StandardUsernamePasswordCredentials creds,
                                        Map<String, String> extraHeaders) throws IOException {
        HttpURLConnection conn = openConnection(urlStr);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept",       "application/json, */*;q=0.8");
        applyAuth(conn, creds);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
        }

        try (OutputStream os = conn.getOutputStream()) {
            os.write((jsonBody == null ? "" : jsonBody).getBytes(StandardCharsets.UTF_8));
        }
        return readResponseFull(conn, urlStr);
    }

    // ── internal ──────────────────────────────────────────────────────────

    private static void applyAuth(HttpURLConnection conn,
                                  StandardUsernamePasswordCredentials creds) {
        if (creds == null) return;
        String auth    = creds.getUsername() + ":" + Secret.toString(creds.getPassword());
        String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        conn.setRequestProperty("Authorization", "Basic " + encoded);
    }

    private static String readResponse(HttpURLConnection conn, String urlStr) throws IOException {
        return readResponseFull(conn, urlStr).body;
    }

    private static Response readResponseFull(HttpURLConnection conn, String urlStr) throws IOException {
        int code = conn.getResponseCode();
        if (code >= 400) {
            String err = "";
            try (InputStream es = conn.getErrorStream()) {
                if (es != null) err = new String(es.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ignored) { /* ignore */ }
            throw new IOException("HTTP " + code + " from " + urlStr
                    + (err.isEmpty() ? "" : " \u2014 body: " + err));
        }
        try (InputStream is = conn.getInputStream()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            // Snapshot headers before disconnect: case-insensitive (lower-cased keys).
            Map<String, String> hdrs = new LinkedHashMap<>();
            Map<String, List<String>> raw = conn.getHeaderFields();
            if (raw != null) {
                for (Map.Entry<String, List<String>> e : raw.entrySet()) {
                    if (e.getKey() == null) continue;
                    List<String> vs = e.getValue();
                    if (vs == null || vs.isEmpty()) continue;
                    hdrs.put(e.getKey().toLowerCase(java.util.Locale.ROOT), vs.get(0));
                }
            }
            return new Response(body, hdrs);
        } finally {
            conn.disconnect();
        }
    }
}
