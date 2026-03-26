package com.activepulse.agent.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HttpSyncClient — POSTs a JSON string to the server.
 *
 * Features:
 *   - Java 11 built-in HttpClient (no extra dependency)
 *   - Bearer token auth via Authorization header
 *   - Exponential backoff retry (up to MAX_RETRIES attempts)
 *   - Returns SyncResult so caller decides how to handle failures
 */
public class HttpSyncClient {

    private static final Logger log = LoggerFactory.getLogger(HttpSyncClient.class);

    private final HttpClient httpClient;

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile HttpSyncClient instance;
    private HttpSyncClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(SyncConfig.CONNECT_TIMEOUT_SEC))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public static HttpSyncClient getInstance() {
        if (instance == null) {
            synchronized (HttpSyncClient.class) {
                if (instance == null) instance = new HttpSyncClient();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────

    /**
     * POSTs the JSON payload to the configured server URL.
     * Retries up to MAX_RETRIES times with exponential backoff.
     *
     * @param  syncId  used only for log context
     * @param  json    serialized SyncPayload JSON string
     * @return SyncResult with HTTP status code or error message
     */
    public SyncResult post(String syncId, String json) {
        SyncConfig cfg = SyncConfig.getInstance();
        String url     = cfg.getServerUrl();
        String apiKey  = cfg.getApiKey();

        int     attempt     = 0;
        long    delayMs     = SyncConfig.RETRY_BASE_DELAY_MS;
        String  lastError   = null;

        while (attempt < SyncConfig.MAX_RETRIES) {
            attempt++;
            log.info("HTTP sync attempt {}/{} — syncId={} url={}",
                    attempt, SyncConfig.MAX_RETRIES, syncId, url);
            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(SyncConfig.REQUEST_TIMEOUT_SEC))
                        .header("Content-Type", "application/json")
                        .header("Accept",       "application/json")
                        .header("X-Agent-Version", "1.0.0")
                        .POST(HttpRequest.BodyPublishers.ofString(json));

                // Auth header — only if apiKey is set
                if (apiKey != null && !apiKey.isBlank()
                        && !apiKey.equals("YOUR_API_KEY_HERE")) {
                    reqBuilder.header("Authorization", "Bearer " + apiKey);
                }

                HttpResponse<String> response = httpClient.send(
                        reqBuilder.build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                int status = response.statusCode();
                log.info("HTTP response: {} for syncId={}", status, syncId);

                if (status >= 200 && status < 300) {
                    return SyncResult.success(status, response.body());
                }

                // 4xx — don't retry (bad request, auth failure, etc.)
                if (status >= 400 && status < 500) {
                    log.warn("HTTP {}  — not retrying syncId={} body={}",
                            status, syncId, truncate(response.body(), 200));
                    return SyncResult.failure(status, "HTTP " + status + ": " + response.body());
                }

                // 5xx — server error, retry
                lastError = "HTTP " + status;
                log.warn("HTTP {} — will retry. syncId={}", status, syncId);

            } catch (java.net.ConnectException e) {
                lastError = "Connection refused: " + e.getMessage();
                log.warn("Connection failed (attempt {}/{}): {}", attempt, SyncConfig.MAX_RETRIES, lastError);
            } catch (java.net.http.HttpTimeoutException e) {
                lastError = "Timeout: " + e.getMessage();
                log.warn("Request timed out (attempt {}/{}): {}", attempt, SyncConfig.MAX_RETRIES, lastError);
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("HTTP error (attempt {}/{}): {}", attempt, SyncConfig.MAX_RETRIES, lastError);
            }

            // Exponential backoff before next retry
            if (attempt < SyncConfig.MAX_RETRIES) {
                log.info("Waiting {}ms before retry...", delayMs);
                try { Thread.sleep(delayMs); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                delayMs *= 2;  // double the delay each attempt
            }
        }

        log.error("All {} HTTP sync attempts failed for syncId={} — last error: {}",
                SyncConfig.MAX_RETRIES, syncId, lastError);
        return SyncResult.failure(0, lastError);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Result
    // ─────────────────────────────────────────────────────────────────

    public record SyncResult(boolean success, int statusCode, String message) {

        public static SyncResult success(int code, String body) {
            return new SyncResult(true, code, body);
        }

        public static SyncResult failure(int code, String error) {
            return new SyncResult(false, code, error);
        }

        @Override
        public String toString() {
            return success
                    ? "SUCCESS(HTTP " + statusCode + ")"
                    : "FAILED(HTTP " + statusCode + " — " + message + ")";
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Utility
    // ─────────────────────────────────────────────────────────────────

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}
