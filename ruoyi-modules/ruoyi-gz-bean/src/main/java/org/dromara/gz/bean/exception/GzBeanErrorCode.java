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

    /** 座位已被占用（DB UNIQUE 兜底命中，doc/10 §3.E1） */
    public static final int SEAT_TAKEN = 4002;
    public static final String SEAT_TAKEN_MSG = "座位已被预约，请重选";

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

    private GzBeanErrorCode() {
    }
}
