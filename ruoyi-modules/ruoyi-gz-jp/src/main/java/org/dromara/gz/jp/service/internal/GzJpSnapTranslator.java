package org.dromara.gz.jp.service.internal;
import com.aliyun.alimt20181012.Client;
import com.aliyun.alimt20181012.models.TranslateGeneralRequest;
import com.aliyun.alimt20181012.models.GetBatchTranslateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import com.aliyun.teaopenapi.models.Config;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.jp.config.GzJpSnapSearchProperties;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
@Slf4j @Service @RequiredArgsConstructor
public class GzJpSnapTranslator {
    private final GzJpSnapSearchProperties properties;
    private final GzJpSnapReadCache cache;
    public String translate(String text, String source, String target, String scope) {
        if (text == null || text.isBlank()) return text == null ? "" : text;
        String key = "translation:" + source + ":" + target + ":" + scope + ":" + hash(text);
        String hit = cache.get(key);
        if (hit != null) return hit;
        try {
            String translated = perform(text, source, target);
            if (translated == null || translated.isBlank()) throw new IllegalStateException("Empty translation");
            cache.put(key, translated, Duration.ofDays(7));
            return translated;
        } catch (Exception ex) {
            // Do not log source text or SDK response (can contain signed URLs / credentials).
            log.warn("Snap translation unavailable: {}", ex.getClass().getSimpleName());
            return text;
        }
    }
    /** First-page titles in one SDK batch per 8,000 characters; caches are per external ID + title hash. */
    public Map<String, String> titles(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, String> missing = new LinkedHashMap<>();
        source.forEach((id, title) -> {
            String hit = cache.get("translation:ja:zh:title:" + id + ":" + hash(title));
            if (hit != null) result.put(id, hit); else missing.put(id, title);
        });
        if (missing.isEmpty()) return result;
        if (!"aliyun".equals(properties.getTranslate().getProvider())) {
            missing.forEach((id, title) -> result.put(id, translate(title, "ja", "zh", "title:" + id)));
            return result;
        }
        Map<String, String> batch = new LinkedHashMap<>(); int length = 0;
        for (var entry : missing.entrySet()) {
            if (entry.getValue().length() > 1000) { result.put(entry.getKey(), entry.getValue()); continue; }
            if (length + entry.getValue().length() > 8000) { translateBatch(batch, result); batch.clear(); length = 0; }
            batch.put(entry.getKey(), entry.getValue()); length += entry.getValue().length();
        }
        if (!batch.isEmpty()) translateBatch(batch, result);
        return result;
    }
    private void translateBatch(Map<String, String> source, Map<String, String> result) {
        result.putAll(source);
        try {
            var response = client().getBatchTranslate(new GetBatchTranslateRequest().setApiType("translate_standard").setScene("general")
                .setFormatType("text").setSourceLanguage("ja").setTargetLanguage("zh").setSourceText(new ObjectMapper().writeValueAsString(source))).getBody();
            if (response.getCode() != 200 || response.getTranslatedList() == null) throw new IllegalStateException("Batch translation rejected");
            for (var entry : response.getTranslatedList()) {
                String id = String.valueOf(entry.get("index")); Object output = entry.get("translated");
                if (!source.containsKey(id) || !"200".equals(String.valueOf(entry.get("code"))) || !(output instanceof String title) || title.isBlank()) continue;
                result.put(id, title);
                cache.put("translation:ja:zh:title:" + id + ":" + hash(source.get(id)), title, Duration.ofDays(7));
            }
        } catch (Exception ex) { log.warn("Snap title batch unavailable: {}", ex.getClass().getSimpleName()); }
    }
    private Client client() throws Exception {
        var p = properties.getTranslate();
        if (p.getAccessKeyId().isBlank() || p.getAccessKeySecret().isBlank()) throw new IllegalStateException("Translation credentials missing");
        return new Client(new Config().setAccessKeyId(p.getAccessKeyId()).setAccessKeySecret(p.getAccessKeySecret())
            .setEndpoint(p.getEndpoint()).setConnectTimeout(2000).setReadTimeout(3000));
    }
    protected String perform(String text, String source, String target) throws Exception {
        var p = properties.getTranslate();
        if ("mock".equals(p.getProvider())) {
            if ("ja".equals(target)) return Map.of("徽章", "缶バッジ", "亚克力立牌", "アクリルスタンド", "蓝色监狱", "ブルーロック").getOrDefault(text, text);
            return "[译]" + text;
        }
        if (!"aliyun".equals(p.getProvider())) throw new IllegalStateException("Unsupported translation provider");
        if (p.getAccessKeyId().isBlank() || p.getAccessKeySecret().isBlank()) throw new IllegalStateException("Translation credentials missing");
        Client client = client();
        var result = client.translateGeneral(new TranslateGeneralRequest().setFormatType("text").setSourceLanguage(source)
            .setTargetLanguage(target).setSourceText(text));
        if (result.getBody().getCode() != 200 || result.getBody().getData() == null) throw new IllegalStateException("Translation rejected");
        return result.getBody().getData().getTranslated();
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
