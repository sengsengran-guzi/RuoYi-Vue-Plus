package org.dromara.gz.common.wechat;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.gz.common.wechat.impl.WxRealAccessTokenManager;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信「运力 id 列表」({@code get_delivery_list}) 解析器 —— 快递公司中文名 → 微信 {@code delivery_id}。
 *
 * <pre>
 * POST https://api.weixin.qq.com/cgi-bin/express/delivery/open_msg/get_delivery_list?access_token=ACCESS_TOKEN
 * 请求体 {}    返回 { errcode, count, delivery_list: [{ delivery_id, delivery_name }, ...] }
 * </pre>
 *
 * <p><b>★ 为什么必须运行时反查，不能硬编码一张编码表</b>：{@code upload_shipping_info} 的
 * {@code express_company} 要求填微信运力 id，而<b>微信官方文档没有给出完整静态编码表</b> ——
 * 运力共 1379 家，文档里只举了 {@code YD}（韵达速递）/ {@code STO} / {@code JTSD} / {@code DHL} 几个例子，
 * 顺丰、中通、圆通、京东、邮政的编码<b>文档中一个都没列</b>。凭印象硬编码的后果不是报错而是
 * <b>客人在微信订单里看到错误的物流公司</b>（或编码非法被拒），所以按中文名向微信要。</p>
 *
 * <p><b>缓存</b>：整张表 Redis 缓存 24h（key 全局单槽 —— 运力表与 appid 无关，两个小程序共用）。
 * 首次 / 过期时拉一次全量（约 1379 条），命中即返回。拉取失败时<b>返回空表而不是抛异常</b>：
 * 发货上报是 best-effort 的次要链路，绝不能因为运力表拉不到就把上报流程整个搞挂 ——
 * 上层拿不到编码会落一条带人话原因的 {@code last_error} 等下次重试。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-301)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxDeliveryListResolver {

    /** 获取运力 id 列表接口（不支持云调用）。 */
    private static final String DELIVERY_LIST_URL =
        "https://api.weixin.qq.com/cgi-bin/express/delivery/open_msg/get_delivery_list";

    /** HTTP 超时（毫秒）。 */
    private static final int HTTP_TIMEOUT_MS = 5000;

    /** 运力表 Redis 缓存 key（全局单槽：运力表与小程序无关）。 */
    private static final String CACHE_KEY = "wx:express:delivery_list";

    /** 运力表缓存 TTL —— 运力增删是低频事件，一天一拉足够。 */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final WxRealAccessTokenManager accessTokenManager;

    /**
     * 快递公司中文名 → 微信 {@code delivery_id}。
     *
     * <p>先精确匹配（{@code 韵达速递} → {@code YD}）；精确匹配不到时退一步做<b>包含匹配</b>
     * （本项目字典写「德邦」而微信可能叫「德邦快递」/「德邦物流」）。仍匹配不到返回 null，
     * 由调用方给出人话错误，<b>不猜</b>。</p>
     *
     * @param carrierName 快递公司中文名（本项目 {@code gz_express_carrier} 字典 label）
     * @param clientId    取 access_token 用的小程序 clientid（blank → 默认小程序）
     * @return 微信运力 id；解析不出返回 null
     */
    public String resolveByName(String carrierName, String clientId) {
        String name = StrUtil.trimToNull(carrierName);
        if (name == null) {
            return null;
        }
        Map<String, String> table = loadTable(clientId);
        if (table.isEmpty()) {
            return null;
        }
        String exact = table.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> e : table.entrySet()) {
            String wxName = e.getKey();
            if (wxName.contains(name) || name.contains(wxName)) {
                log.info("[wx-express] 快递公司「{}」按包含匹配到微信运力「{}」→ {}", name, wxName, e.getValue());
                return e.getValue();
            }
        }
        log.warn("[wx-express] 快递公司「{}」在微信运力表（{} 家）里找不到对应编码", name, table.size());
        return null;
    }

    /** 取运力表（缓存优先）：{@code delivery_name → delivery_id}。拉取失败返回空表，不抛。 */
    private Map<String, String> loadTable(String clientId) {
        Map<String, String> cached = RedisUtils.getCacheObject(CACHE_KEY);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        Map<String, String> fetched = fetch(clientId);
        if (!fetched.isEmpty()) {
            RedisUtils.setCacheObject(CACHE_KEY, fetched, CACHE_TTL);
        }
        return fetched;
    }

    /** 真调微信拉全量运力表。任何异常一律降级为空表 + 告警（不拖垮发货上报）。 */
    private Map<String, String> fetch(String clientId) {
        Map<String, String> table = new LinkedHashMap<>();
        try {
            String url = DELIVERY_LIST_URL + "?access_token=" + accessTokenManager.getTokenFor(clientId, false);
            String body = HttpUtil.createPost(url)
                .header("Content-Type", "application/json;charset=UTF-8")
                .body("{}")
                .timeout(HTTP_TIMEOUT_MS)
                .execute()
                .body();
            JSONObject json = JSONUtil.parseObj(body);
            Integer errcode = json.getInt("errcode");
            if (errcode != null && errcode != 0) {
                log.warn("[wx-express] 拉取运力表失败 errcode={} errmsg={}", errcode, json.getStr("errmsg"));
                return table;
            }
            JSONArray list = json.getJSONArray("delivery_list");
            if (list == null) {
                log.warn("[wx-express] 运力表响应缺 delivery_list：{}", StrUtil.maxLength(body, 200));
                return table;
            }
            for (int i = 0; i < list.size(); i++) {
                JSONObject item = list.getJSONObject(i);
                String id = item.getStr("delivery_id");
                String name = item.getStr("delivery_name");
                if (StrUtil.isNotBlank(id) && StrUtil.isNotBlank(name)) {
                    table.put(name, id);
                }
            }
            log.info("[wx-express] 运力表拉取成功，共 {} 家，已缓存 {}h", table.size(), CACHE_TTL.toHours());
        } catch (Exception e) {
            log.error("[wx-express] 拉取运力表异常（降级为空表，本次上报将带人话原因失败并等重试）", e);
        }
        return table;
    }
}
