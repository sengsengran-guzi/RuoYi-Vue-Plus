package org.dromara.gz.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.wechat.WxAppResolver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * mp 公开 sys_config key 的<b>白名单 + 按小程序分身</b>解析器（多小程序 ADR-0019 §1 的配置维度）。
 *
 * <p><b>解决什么</b>：同一后端服务多个小程序（谷子宇宙 {@code mp-applet-sensenran-guzi} /
 * 谷子宇宙拼团 {@code mp-applet-gz-jp}），而 {@code sys_config} 是<b>全局单表</b>。首页轮播 banner
 * 这类<b>运营素材</b>天然是「每个小程序各配一套」的：若两个小程序读同一个 {@code config_key}，
 * 运营在后台配一次 → 拼团首页会显示拼豆的图（反之亦然）。</p>
 *
 * <p><b>怎么解决</b>：把「一个用途」抽象成一个 <b>key 族</b>（{@link KeyFamily}）——
 * 族里登记该用途在各小程序下的<b>物理 config_key</b>，默认 key = 谷子宇宙（线上既有）那份，
 * 未登记分身的小程序继承默认 key。「当前请求属于哪个小程序」的判断<b>只在本类发生一次</b>
 * （委托 {@link WxAppResolver#currentClientId()} 读 header {@code clientid}），
 * 调用方（controller）不需要也不应该自己判断 clientid。</p>
 *
 * <p><b>为什么用「族」而不是让前端各传各的 key 后端照单全收</b>：两个 mp 端确实各自硬编码自己的 key
 * （可读性），但仅靠前端自律不足以防串味 —— 拼团页面往往是从拼豆页面拷来的，一旦漏改常量就会
 * 静默读到另一个小程序的素材。本类按<b>当前 clientid</b> 把请求 key 归一到本小程序自己那份，
 * 属于自愈：拼团误传 {@code gz.home.banners} 也只会读到 {@code gz.jp.home.banners}。</p>
 *
 * <p><b>安全边界不变</b>：不属于任何族的 key 一律返回 {@code null}（controller 拒绝），
 * 仍然只放行运营素材类配置，不暴露分成率 / 默认密码等敏感 key。</p>
 *
 * <p><b>新增小程序 / 新增公开 key 时改这里一处</b>：在 {@link #FAMILIES} 里加一行；
 * 若新 key 需要按小程序分身，物理 key 必须在 Flyway 迁移里建好默认值，并让 admin 能配。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MpPublicConfigKeyResolver {

    /** 谷子宇宙（拼豆，线上既有）小程序 clientid —— 它的物理 key 永远是历史 key，不可改。 */
    public static final String CLIENT_ID_GUZI = "mp-applet-sensenran-guzi";

    /** 谷子宇宙拼团（日本拼团）小程序 clientid。 */
    public static final String CLIENT_ID_JP = "mp-applet-gz-jp";

    /** 首页轮播 banner —— 谷子宇宙那份（线上既有，GZ-HOME-001）。 */
    public static final String KEY_HOME_BANNERS = "gz.home.banners";

    /** 首页轮播 banner —— 拼团那份（GZ-JP-201）。 */
    public static final String KEY_JP_HOME_BANNERS = "gz.jp.home.banners";

    /** 拼豆落地页顶部 banner（GZ-BEAN-010）—— 仅谷子宇宙小程序在用，无分身。 */
    public static final String KEY_BEAN_HOME_BANNER = "gz.bean.home.banner";

    /** 回收提交页客服微信二维码（GZ-RECYCLE-016）—— 回收是 sensenran 专属业务，无分身。 */
    public static final String KEY_RECYCLE_SERVICE_QRCODE = "gz.recycle.serviceQrcode";

    /**
     * 公开 key 族登记表（= 原 controller 里的白名单，升级成「按小程序分身」）。
     *
     * <p>每个族 = 一个用途；默认 key 是谷子宇宙（线上）那份，overrides 里登记其它小程序的分身。</p>
     */
    private static final List<KeyFamily> FAMILIES = List.of(
        // 拼豆落地页 banner：只有谷子宇宙小程序在用，拼团端不读它 → 无分身
        new KeyFamily(KEY_BEAN_HOME_BANNER, Map.of()),
        // 回收提交页客服二维码：回收是 sensenran 专属业务，拼团端不读它 → 无分身
        new KeyFamily(KEY_RECYCLE_SERVICE_QRCODE, Map.of()),
        // ★ 首页轮播 banner：两个小程序各一份，否则运营配一次两边串味
        new KeyFamily(KEY_HOME_BANNERS, Map.of(CLIENT_ID_JP, KEY_JP_HOME_BANNERS))
    );

    /** 物理 key → 所属族（含各小程序的分身 key），启动期物化。 */
    private static final Map<String, KeyFamily> FAMILY_BY_KEY = buildIndex();

    private final WxAppResolver appResolver;

    /**
     * 把 mp 端请求的 key 归一为「当前小程序自己那份」物理 config_key。
     *
     * @param requestedKey mp 端传来的 config_key（前端硬编码常量）
     * @return 实际应读取的物理 key；{@code requestedKey} 不在白名单内时返回 {@code null}（调用方须拒绝）
     */
    public String resolve(String requestedKey) {
        KeyFamily family = FAMILY_BY_KEY.get(requestedKey);
        if (family == null) {
            return null;
        }
        // 「当前是哪个小程序」全项目只在这里判一次；无 header（curl / 非 mp 调用方）回落默认小程序，
        // 即多小程序改造前的行为 —— 谷子宇宙那份，线上零变化。
        String clientId = appResolver.currentClientId();
        String actualKey = family.keyFor(clientId);
        if (!actualKey.equals(requestedKey)) {
            log.debug("[gz-config-mp] clientid={} 请求 key={} → 归一到本小程序自己的 key={}",
                clientId, requestedKey, actualKey);
        }
        return actualKey;
    }

    /** 全部已登记的物理 key（含各小程序分身）—— 单测 / 排查用。 */
    public static Set<String> allPublicKeys() {
        return FAMILY_BY_KEY.keySet();
    }

    private static Map<String, KeyFamily> buildIndex() {
        Map<String, KeyFamily> index = new LinkedHashMap<>();
        for (KeyFamily family : FAMILIES) {
            for (String key : family.allKeys()) {
                KeyFamily prev = index.put(key, family);
                if (prev != null && prev != family) {
                    // 同一个物理 key 落在两个族里 = 登记表写错，启动即炸（别等运行期读到别人的素材）
                    throw new IllegalStateException("mp 公开配置 key 重复登记: " + key);
                }
            }
        }
        return Map.copyOf(index);
    }

    /**
     * 一个「用途」在各小程序下的 config_key 集合。
     *
     * @param defaultKey      默认（谷子宇宙 / 无 clientid 上下文）用的物理 key
     * @param keyByClientId   按 clientid 覆盖的分身 key；未登记的小程序继承 {@code defaultKey}
     */
    private record KeyFamily(String defaultKey, Map<String, String> keyByClientId) {

        String keyFor(String clientId) {
            return keyByClientId.getOrDefault(clientId, defaultKey);
        }

        Set<String> allKeys() {
            Set<String> keys = new java.util.LinkedHashSet<>();
            keys.add(defaultKey);
            keys.addAll(keyByClientId.values());
            return keys;
        }
    }
}
