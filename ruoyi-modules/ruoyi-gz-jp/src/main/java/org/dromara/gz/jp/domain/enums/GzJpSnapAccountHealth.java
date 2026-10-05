package org.dromara.gz.jp.domain.enums;

/**
 * 采购账号健康状态 —— <b>只由 ruoyi 心跳任务按日本服务 {@code /v1/health} 写</b>（admin 启停是另一列 {@code admin_enabled}）。
 *
 * <p>字段：{@code FIELD:gz_jp_snap_account.health_status}；取值与字典 {@code gz_jp_snap_account_health}（GZ-SNAP-101 迁移
 * {@code V202610050725}）一一对应，{@code GzJpSnapEnumDictTest} 守漂移。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
public enum GzJpSnapAccountHealth {

    /** 正常 */
    HEALTHY("healthy", "正常"),

    /** 触发验证 */
    CHALLENGE("challenge", "触发验证"),

    /** 登录失效 */
    LOGGED_OUT("logged_out", "登录失效"),

    /** 不可达 */
    UNREACHABLE("unreachable", "不可达"),

    /** 未知 */
    UNKNOWN("unknown", "未知");

    private final String code;
    private final String label;

    GzJpSnapAccountHealth(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * code → 中文文案。
     *
     * @param code 存库值（可能为 null）
     * @return 中文；null / 未知 code 原样返回
     */
    public static String labelOf(String code) {
        GzJpSnapAccountHealth v = of(code);
        return v == null ? code : v.label;
    }

    /**
     * code 是否为合法取值（如实回答，不静默回落）。
     *
     * @param code 待校验值
     * @return true = 合法
     */
    public static boolean isValid(String code) {
        return of(code) != null;
    }

    /**
     * code → 枚举。
     *
     * @param code 存库值
     * @return 对应枚举；null / 未知 code 返回 null
     */
    public static GzJpSnapAccountHealth of(String code) {
        for (GzJpSnapAccountHealth v : values()) {
            if (v.code.equals(code)) {
                return v;
            }
        }
        return null;
    }
}
