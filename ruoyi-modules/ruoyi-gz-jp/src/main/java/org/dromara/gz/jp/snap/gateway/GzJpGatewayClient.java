package org.dromara.gz.jp.snap.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Component
public class GzJpGatewayClient {
    public enum Endpoint {
        SEARCH(3, true), ITEMS(5, true), PURCHASES(45, false), RECONCILE(10, true),
        TRADES(15, true), HEALTH(5, true), SELLERS(5, true), RESOLVE(5, true);
        final int seconds;
        final boolean read;
        Endpoint(int seconds, boolean read) { this.seconds = seconds; this.read = read; }
    }
    private static class Circuit { int failures; long until; boolean probing; }
    private final Map<Endpoint, Circuit> circuits = new ConcurrentHashMap<>();
    private final GzJpGatewayProperties properties;
    private final ObjectMapper json;
    private final Executor purchaseExecutor;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public GzJpGatewayClient(GzJpGatewayProperties properties, ObjectMapper json,
                            @Qualifier("snapPurchaseExecutor") Executor purchaseExecutor) {
        this.properties = properties;
        this.json = json;
        this.purchaseExecutor = purchaseExecutor;
    }
    public JsonNode health() { return request(Endpoint.HEALTH, "GET", "/v1/health", null); }
    public JsonNode search(String keyword, String sort, int page, String categoryIds) {
        return request(Endpoint.SEARCH, "GET", "/v1/search?q=" + encode(keyword) + "&sort=" + encode(sort)
            + "&page=" + page + "&category_ids=" + encode(categoryIds), null);
    }
    public JsonNode item(String externalId) { return request(Endpoint.ITEMS, "GET", "/v1/items/" + encode(externalId), null); }
    public JsonNode seller(String sellerId) { return request(Endpoint.SELLERS, "GET", "/v1/sellers/" + encode(sellerId), null); }
    public JsonNode resolve(String url) { return request(Endpoint.RESOLVE, "GET", "/v1/resolve?url=" + encode(url), null); }
    public JsonNode trades(String accountCode) { return request(Endpoint.TRADES, "GET", "/v1/trades?account_code=" + encode(accountCode), null); }
    public CompletableFuture<JsonNode> purchase(Object body) {
        return CompletableFuture.supplyAsync(() -> request(Endpoint.PURCHASES, "POST", "/v1/purchases", body), purchaseExecutor);
    }
    public CompletableFuture<JsonNode> reconcile(String orderNo, String externalId, String accountCode) {
        return CompletableFuture.supplyAsync(() -> request(Endpoint.RECONCILE, "GET", "/v1/purchases/" + encode(orderNo)
            + "/reconcile?external_id=" + encode(externalId) + "&account_code=" + encode(accountCode), null), purchaseExecutor);
    }
    private JsonNode request(Endpoint endpoint, String method, String target, Object body) {
        if (properties.getSecret().isBlank()) {
            throw new GzJpGatewayException(GzJpGatewayException.Kind.CONFIGURATION, 0, "Gateway secret is not configured", null);
        }
        Circuit circuit = endpoint.read ? circuits.computeIfAbsent(endpoint, key -> new Circuit()) : null;
        if (circuit != null) synchronized (circuit) {
            if (circuit.until > System.currentTimeMillis() || circuit.probing) {
                throw new GzJpGatewayException(GzJpGatewayException.Kind.CIRCUIT_OPEN, 0, "Gateway read circuit open: " + endpoint, null);
            }
            if (circuit.until != 0) circuit.probing = true;
        }
        try {
            byte[] payload = body == null ? new byte[0] : json.writeValueAsBytes(body);
            String ts = Long.toString(Instant.now().getEpochSecond());
            URI uri = URI.create(properties.getBaseUrl().replaceAll("/+$", "") + target);
            String wireTarget = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(endpoint.seconds))
                .header("X-GW-Ts", ts).header("X-GW-Sign", sign(properties.getSecret(), ts, method, wireTarget, payload))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new GzJpGatewayException(GzJpGatewayException.Kind.HTTP_STATUS, response.statusCode(), "Gateway HTTP " + response.statusCode(), null);
            }
            JsonNode result = json.readTree(response.body());
            if (result == null) throw new java.io.IOException("Empty gateway response");
            if (circuit != null) synchronized (circuit) { circuit.failures = 0; circuit.until = 0; circuit.probing = false; }
            return result;
        } catch (Exception ex) {
            if (circuit != null) synchronized (circuit) {
                circuit.probing = false;
                if (++circuit.failures >= properties.getReadFailureThreshold()) circuit.until = System.currentTimeMillis() + properties.getCircuitOpenMs();
            }
            if (ex instanceof GzJpGatewayException gateway) throw gateway;
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new GzJpGatewayException(ex instanceof HttpTimeoutException ? GzJpGatewayException.Kind.TIMEOUT : GzJpGatewayException.Kind.IO,
                0, "Gateway call failed: " + endpoint, ex);
        }
    }
    public static String sign(String secret, String ts, String method, String target, byte[] body) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((ts + "\n" + method.toUpperCase(java.util.Locale.ROOT) + "\n" + target + "\n" + hash).getBytes(StandardCharsets.UTF_8)));
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
