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

    private GzBeanErrorCode() {
    }
}
