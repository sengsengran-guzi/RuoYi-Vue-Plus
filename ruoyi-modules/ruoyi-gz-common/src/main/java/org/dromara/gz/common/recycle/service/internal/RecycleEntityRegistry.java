package org.dromara.gz.common.recycle.service.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回收站实体注册表（GZ-ADMIN-108 / D1 显式注册不反射扫表）。
 *
 * <p>只纳入 <b>owner 可能误删需恢复的业务主数据表</b>（CRUD 主数据，非流水/审计/快照表）。
 * 表名 + 名称列以 §0 自检 grep migration 真实落表为准（doc/11 §0.3 del_flag 仅 2 值）：</p>
 * <ul>
 *   <li>news        → gz_news_article(title)</li>
 *   <li>ord_product → gz_ord_product(name)</li>
 *   <li>ord_sku     → gz_ord_sku(spec_name)</li>
 *   <li>gacha_prize → gz_gacha_prize(name)</li>
 *   <li>gacha_machine → gz_gacha_machine(name)</li>
 *   <li>bean_store  → gz_bean_store(name)</li>
 * </ul>
 *
 * <p><b>安全</b>：泛型 SQL 用 {@code ${tableName}} / {@code ${nameColumn}} 拼接，但取值<b>仅来自本注册表
 * 白名单</b>（非用户输入），不存在 SQL 注入；entityType 非法 → {@link #require} 抛 400。</p>
 *
 * <p>gz-common 是 base 模块（gz-ord / gz-news / gz-gacha 反向依赖它），无法 import 各业务 Service，
 * 故 restore/archive/cleanup 走本注册表 + 泛型表名 SQL，不路由到各模块 Service（避免循环依赖）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-108)
 */
public final class RecycleEntityRegistry {

    /**
     * 单条实体注册定义。
     *
     * @param entityType   稳定 code（前端枚举 / restore body 用）
     * @param tableName    真实表名（白名单，§0 自检确认）
     * @param nameColumn   展示名称列
     * @param label        中文实体类型标签
     */
    public record Def(String entityType, String tableName, String nameColumn, String label) {
    }

    /** 注册清单（LinkedHashMap 保序，前端下拉按此序）。 */
    private static final Map<String, Def> REGISTRY = new LinkedHashMap<>();

    static {
        register(new Def("news", "gz_news_article", "title", "资讯文章"));
        register(new Def("ord_product", "gz_ord_product", "name", "预购商品"));
        register(new Def("ord_sku", "gz_ord_sku", "spec_name", "预购 SKU"));
        register(new Def("gacha_prize", "gz_gacha_prize", "name", "扭蛋奖品"));
        register(new Def("gacha_machine", "gz_gacha_machine", "name", "扭蛋机"));
        register(new Def("bean_store", "gz_bean_store", "name", "拼豆门店"));
    }

    private RecycleEntityRegistry() {
    }

    private static void register(Def def) {
        REGISTRY.put(def.entityType(), def);
    }

    /** 全部注册定义（按注册顺序）。 */
    public static List<Def> all() {
        return List.copyOf(REGISTRY.values());
    }

    /** 按 entityType 取定义（无则 null）。 */
    public static Def get(String entityType) {
        return REGISTRY.get(entityType);
    }

    /** 按 entityType 取定义，非法 → 抛 IllegalArgumentException（controller 转 400）。 */
    public static Def require(String entityType) {
        Def def = REGISTRY.get(entityType);
        if (def == null) {
            throw new IllegalArgumentException("未注册的回收站实体类型: " + entityType);
        }
        return def;
    }
}
