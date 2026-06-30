package org.dromara.gz.bean.exception;

/**
 * 拼豆域业务错误码（GZ-BEAN-004）。
 *
 * <p>设计原则（doc/10 §3 异常分支映射）：</p>
 * <ul>
 *   <li>code 为业务可识别字符串（如 {@code PHONE_REQUIRED}），mp 端按 code 决定 UX
 *       — 比起按 message 字符串匹配稳定</li>
 *   <li>msg 为给用户看的中文（mp 端可直接 toast 此 msg / 也可自己映射文案）</li>
 *   <li>http status 在 {@code ServiceException} 体系下统一 200（业务错），由 mp 端按 R.code 分流</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
public final class GzBeanErrorCode {

    /** 手机号未绑定，需走 wx.getPhoneNumber 流程（doc/10 §1.N8 / §3.N6） */
    public static final int PHONE_REQUIRED = 4001;
    public static final String PHONE_REQUIRED_MSG = "拼豆预约需提供手机号，请先授权";

    /**
     * 具体座位区间被占（GZ-BEAN-024 影院选座防超卖，ADR-0015 §2 / doc/11 §3.6）。
     *
     * <p>下单事务内对 {@code (store, seat_id, sess_date)} 活跃单 {@code FOR UPDATE} 判区间重叠，命中即拒单
     * 整笔回滚。<b>码偏差说明</b>：ADR-0015 / doc/11 §3.6 建议码 4012，但 4012 已被
     * {@link #SEAT_TYPE_NOT_CONFIGURED} 占用（早于本批落地）；本码沿用既有 {@code SEAT_TAKEN=4002} 常量
     * （语义即「座位已被占」，已接入 mp）。mp/admin 端按本常量对接（4002），不要按文档 4012。</p>
     */
    public static final int SEAT_TAKEN = 4002;
    public static final String SEAT_TAKEN_MSG = "该座位该时段已被预约，请重选座位或时段";

    /** 同用户同时段已有 pending 预约（应用层校验） */
    public static final int DUPLICATE_USER_BOOKING = 4003;
    public static final String DUPLICATE_USER_BOOKING_MSG = "您该时段已有预约，请勿重复提交";

    /** 操作过快 / 防连点（Redis 锁拒绝） */
    public static final int SUBMIT_TOO_FAST = 4004;
    public static final String SUBMIT_TOO_FAST_MSG = "操作过快，请稍后再试";

    /** 座位被后台停用（doc/10 §3.E3） */
    public static final int SEAT_DISABLED = 4005;
    public static final String SEAT_DISABLED_MSG = "该座位已停用，请重选";

    /** 时段被后台停用（doc/10 §3.E4） */
    public static final int SLOT_DISABLED = 4006;
    public static final String SLOT_DISABLED_MSG = "该时段已停用";

    /** 预约不存在 */
    public static final int BOOKING_NOT_FOUND = 4007;
    public static final String BOOKING_NOT_FOUND_MSG = "预约不存在";

    /** 状态非法（如核销已核销 / 取消已取消） */
    public static final int INVALID_STATUS = 4008;
    public static final String INVALID_STATUS_MSG = "预约状态不允许此操作";

    /** 核销码 QR payload 格式非法（不是 "BK|{no}|{code}" 三段，doc/10 §3 E8） */
    public static final int QR_PAYLOAD_MALFORMED = 4009;
    public static final String QR_PAYLOAD_MALFORMED_MSG = "核销码格式无法识别，请重新截图";

    /** 核销码签名校验不通过（被篡改 / 非本店码，doc/10 §3 E8） */
    public static final int QR_SIGNATURE_INVALID = 4010;
    public static final String QR_SIGNATURE_INVALID_MSG = "核销码无效或已被篡改";

    /** 扫码命中的状态分支文案（admin 端按 code 映射，doc/10 §3 E6/E7）：
     *  已核销 / 已取消 / 已过期 共用 INVALID_STATUS（4008），由 service 拼当前状态中文返回。*/

    // ============================================================
    //  GZ-BEAN-014 V1.2 付费模型错误码
    // ============================================================

    /** 该 (座位类型,日期,1h 格) 配额已满（区间内某格活跃 booking 数 ≥ quantity，doc/15a §A.6 / ADR-0011 §3） */
    public static final int QUOTA_FULL = 4011;
    public static final String QUOTA_FULL_MSG = "该时段座位已约满，请重选类型或时段";

    /** 座位类型配置不存在 / 未配置（该门店未配此 seat_type） */
    public static final int SEAT_TYPE_NOT_CONFIGURED = 4012;
    public static final String SEAT_TYPE_NOT_CONFIGURED_MSG = "该座位类型暂未开放，请重选";

    /** 座位类型被后台停用（doc/10 §11.E3） */
    public static final int SEAT_TYPE_DISABLED = 4013;
    public static final String SEAT_TYPE_DISABLED_MSG = "该座位类型已停用，请重选";

    /** 付款前未采集微信号（doc/10 §11.N6 / §11.E2） */
    public static final int WECHAT_ID_REQUIRED = 4014;
    public static final String WECHAT_ID_REQUIRED_MSG = "拼豆预约需填写微信号，便于门店联系";

    /** 核销前置未满足：仅 pay_status='paid' 的 pending 单可核销（ADR-0007 §1.2） */
    public static final int NOT_PAID = 4015;
    public static final String NOT_PAID_MSG = "该预约尚未完成支付，不可核销";

    /**
     * 所选 1h 区间不连续 / 跨越休息时段 / 含不可约格（GZ-BEAN-017，ADR-0011 §5 / doc/15a §A.2/§A.6）。
     *
     * <p><b>码偏差说明</b>：doc/15a §A.6 给的建议码是 4015，但 4015 已被 {@link #NOT_PAID} 占用（早于本批落地）。
     * 为不破坏既有 NOT_PAID 契约，本码取下一空位 4016。mp/admin 端按本常量对接（4016），不要按文档 4015。</p>
     */
    public static final int SLOT_RANGE_INVALID = 4016;
    public static final String SLOT_RANGE_INVALID_MSG = "所选时段不连续或跨越休息时段，请重选";

    // ============================================================
    //  GZ-BEAN-026 店内计时看板（ADR-0015 §5 / doc/10 §11 看板子流程异常分支）
    // ============================================================

    /**
     * 看板放座 / 延时操作的预约状态不允许（仅在店使用中 {@code status='used'} 单可放座 / 延时，doc/11 §3.12）。
     * 与核销/取消复用 {@link #INVALID_STATUS}（4008）的语义区分：本码专指看板侧操作前置态不满足，
     * service 拼当前状态中文返回；mp/admin 端按 code 映射文案。
     */
    public static final int BOARD_OP_INVALID_STATUS = 4017;
    public static final String BOARD_OP_INVALID_STATUS_MSG = "该预约非在店使用中，不可执行此看板操作";

    /**
     * 延时撞占（GZ-BEAN-026 E4b，ADR-0015 §5 / doc/10 §11.E4b）：把 slot_end 推后，新增格已被别人占
     * （具体座位区间互斥校验不过）→ 拒绝延时。
     */
    public static final int EXTEND_CONFLICT = 4018;
    public static final String EXTEND_CONFLICT_MSG = "该座位后续时段已被预约，无法延时";

    /** 延时小时数非法（须为正整数 1..N，doc/11 §3.12 延时按整点格推后） */
    public static final int EXTEND_HOURS_INVALID = 4019;
    public static final String EXTEND_HOURS_INVALID_MSG = "延时小时数非法，请输入正整数";

    /**
     * 距时段开始不足 {@code CANCEL_CUTOFF_MINUTES}（20）分钟，不可取消预约。
     *
     * <p>退改时间闸（甲方口径）：开始前 20 分钟内禁止取消退款，防止用户卡点放座 / 用「取消全退」
     * 绕过「未到店爽约钱不退」罚则。对所有单统一生效（含免费 / 全券抵扣单）。mp 端按本 code 隐藏取消按钮 +
     * http.ts 全局 toast 本 msg 兜底。</p>
     */
    public static final int CANCEL_WINDOW_CLOSED = 4020;
    public static final String CANCEL_WINDOW_CLOSED_MSG = "距开始不足 20 分钟，不可取消";

    /**
     * 核销时未分配物理座位（ADR-0016 §3）：新模型单下单不绑座，核销必须由店员现场分配一个空闲座；
     * 该单 seat_id 仍为 NULL 且本次核销未传 seatId → 拒。存量已绑座单不触发本码。
     */
    public static final int SEAT_REQUIRED = 4021;
    public static final String SEAT_REQUIRED_MSG = "请先为该预约分配座位再核销";

    /**
     * 核销分配的座位桌型与预约桌型不符（ADR-0016 §3）：店员分到的物理座位所属
     * seat_type_config_id ≠ 预约的 seat_type_config_id（如把双人桌单分给单人预约）→ 拒。
     */
    public static final int SEAT_TYPE_MISMATCH = 4022;
    public static final String SEAT_TYPE_MISMATCH_MSG = "所选座位的桌型与预约桌型不符，请重选座位";

    private GzBeanErrorCode() {
    }
}
