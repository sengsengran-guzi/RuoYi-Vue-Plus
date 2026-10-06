package org.dromara.gz.jp.service.internal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.common.core.service.ConfigService;
import org.dromara.gz.jp.config.GzJpSnapSearchProperties;
import org.dromara.gz.jp.mapper.*;
import org.dromara.gz.jp.snap.gateway.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import static org.mockito.Mockito.*;
class Snap302Fixture {
    final ObjectMapper json = new ObjectMapper();
    final GzJpGatewayClient gateway = mock(GzJpGatewayClient.class);
    final ConfigService config = mock(ConfigService.class);
    final GzJpSnapTermMapper terms = mock(GzJpSnapTermMapper.class);
    final GzJpSnapItemMapper items = mock(GzJpSnapItemMapper.class);
    final Memory cache = new Memory();
    final GzJpSnapSearchProperties properties = new GzJpSnapSearchProperties();
    final GzJpSnapTranslator translator = new GzJpSnapTranslator(properties, cache);
    final GzJpSnapNormalizer normalizer = new GzJpSnapNormalizer(gateway, translator);
    final GzJpSnapFilter filter = new GzJpSnapFilter();
    final GzJpSnapReadService service = new GzJpSnapReadService(gateway, normalizer, filter, translator, cache, config, terms, items, properties, mock(GzJpSnapRiskControl.class), json);
    Snap302Fixture() {
        when(config.getConfigValue("gz.jp.snap.filter")).thenReturn("{\"categoryWhitelist\":[\"745\",\"975\",\"980\"],\"keywordBlacklist\":[\"専用\"]}");
        when(terms.selectList(any())).thenReturn(List.of());
    }
    JsonNode node(String value) { try { return json.readTree(value); } catch (Exception ex) { throw new IllegalArgumentException(ex); } }
    JsonNode item(String id, String category, String type, String status, String title) {
        return json.createObjectNode().put("external_id", id).put("category_id", category).put("item_type", type).put("status", status)
            .put("title_ja", title).put("price_jpy", 801).put("shipping_payer", "seller").put("seller_id", "99000001")
            .put("thumb_url", "https://static.mercdn.net/item/synthetic.png");
    }
    JsonNode body(JsonNode... items) { var result = json.createObjectNode(); var list = result.putArray("items"); for (var item : items) list.add(item); return result; }
    static class Memory extends GzJpSnapReadCache {
        final Map<String,String> values = new ConcurrentHashMap<>();
        final Map<String,ReentrantLock> locks = new ConcurrentHashMap<>();
        @Override public String get(String key) { return values.get(key); }
        @Override public void put(String key, String value, Duration ttl) { values.put(key, value); }
        @Override public AutoCloseable lock(String key) { var lock = locks.computeIfAbsent(key, k -> new ReentrantLock()); lock.lock(); return lock::unlock; }
    }
}
