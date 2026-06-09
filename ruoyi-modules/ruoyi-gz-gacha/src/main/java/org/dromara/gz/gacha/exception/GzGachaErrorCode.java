package org.dromara.gz.gacha.exception;

/**
 * 扭蛋域业务错误码（GZ-GACHA-101 起）。
 *
 * <p>设计原则同 gz-ord / gz-bean：code 为业务可识别整数（mp/admin 按 code 决定 UX），msg 为给用户看的
 * 中文。http status 在 {@code ServiceException} 体系下统一 200，由前端按 R.code 分流。
 * 段位 8xxx（gz-ord 用 7xxx，避免冲突）。</p>
 *
 * <p><b>盲盒语义纪律</b>（README §B，仅 gacha 域）：用户可见 msg 用扭蛋 / 开盒 / 获得 / 出现概率，
 * 禁抽奖 / 中奖 / 开奖。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
public final class GzGachaErrorCode {

    /** 扭蛋机不存在（含软删） */
    public static final int MACHINE_NOT_FOUND = 8001;
    public static final String MACHINE_NOT_FOUND_MSG = "扭蛋机不存在";

    /** 机器状态非法（如 admin 试图手动设 auto_off，决策 D5） */
    public static final int INVALID_MACHINE_STATUS = 8002;
    public static final String INVALID_MACHINE_STATUS_MSG = "扭蛋机状态不允许此操作";

    /** 机器已被奖品池引用，拒绝删除（先清空奖品池再删机器） */
    public static final int MACHINE_HAS_PRIZE = 8003;
    public static final String MACHINE_HAS_PRIZE_MSG = "扭蛋机仍有奖品，请先清空奖品池再删除";

    /** 奖品不存在（含软删） */
    public static final int PRIZE_NOT_FOUND = 8004;
    public static final String PRIZE_NOT_FOUND_MSG = "奖品不存在";

    /** 奖品归属机器非法（machineId 不存在 / 不属于该机器） */
    public static final int PRIZE_MACHINE_INVALID = 8005;
    public static final String PRIZE_MACHINE_INVALID_MSG = "奖品所属扭蛋机无效";

    /** 稀有度非法（非 SSR/SR/R/N 四档，强约束 #2） */
    public static final int INVALID_RARITY = 8006;
    public static final String INVALID_RARITY_MSG = "稀有度仅支持 SSR / SR / R / N";

    /** 配置态库存 / 权重为负（stock_initial / stock_remain / weight < 0，R2） */
    public static final int NEGATIVE_STOCK_OR_WEIGHT = 8007;
    public static final String NEGATIVE_STOCK_OR_WEIGHT_MSG = "库存与权重不能为负数";

    /** 剩余库存超过初始库存（配置态约束：stock_remain ≤ stock_initial） */
    public static final int REMAIN_EXCEEDS_INITIAL = 8008;
    public static final String REMAIN_EXCEEDS_INITIAL_MSG = "剩余库存不能大于初始库存";

    /**
     * 付款前缺货拦截（GACHA-104 AC2.5，doc/10 §8.N4 / §8.E1）：投币时机器非 on_shelf
     * 或无任何在售有货商品 → 不收款。<b>掏钱之前拦缺货，掏钱之后必有货</b> —— 「无系统退款」成立的前提。
     */
    public static final int MACHINE_EMPTY = 8009;
    public static final String MACHINE_EMPTY_MSG = "该扭蛋机暂时缺货";

    /** 用户信息异常（openid 缺失，需重新登录） */
    public static final int USER_INVALID = 8010;
    public static final String USER_INVALID_MSG = "用户信息异常，请重新登录";

    /**
     * 揭晓结果归属非法（GACHA-105 AC1）：查询的开盒记录 {@code user_id} 非当前登录用户 →
     * 拒绝（不可查他人开盒结果）。盲盒语义：msg 用「开盒记录」不用「中奖记录」。
     */
    public static final int DRAW_FORBIDDEN = 8011;
    public static final String DRAW_FORBIDDEN_MSG = "无权查看该开盒记录";

    private GzGachaErrorCode() {
    }
}
