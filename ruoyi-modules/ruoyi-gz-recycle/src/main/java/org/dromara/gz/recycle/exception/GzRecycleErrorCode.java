package org.dromara.gz.recycle.exception;

/**
 * 回收域业务错误码（GZ-RECYCLE-002，doc/10 §13 异常分支映射）。
 *
 * <p>设计原则同拼豆域 {@code GzBeanErrorCode}：code 为业务可识别整数，mp 端按 R.code 决定 UX；
 * msg 为给用户看的中文（mp 可直接 toast）。ServiceException 体系下 http status 统一 200，按 code 分流。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-002)
 */
public final class GzRecycleErrorCode {

    /** 未上传实物照（AC3 / doc/10 §13.E7）；前端已先拦截，后端兜底 */
    public static final int SUBMIT_IMAGE_REQUIRED = 4101;
    public static final String SUBMIT_IMAGE_REQUIRED_MSG = "请先拍照上传实物再提交";

    /** 含未命中价目区间的品类（doc/10 §13.E1），整单不可提交 */
    public static final int HAS_UNPRICED_CATEGORY = 4102;
    public static final String HAS_UNPRICED_CATEGORY_MSG = "含暂不支持线上估价的品类，请到店咨询";

    /** receiver_openid 缺失 / 无效（doc/10 §13.E5），反向打款必需 → 拦截提交 */
    public static final int OPENID_REQUIRED = 4103;
    public static final String OPENID_REQUIRED_MSG = "请重新授权微信登录后再提交回收";

    /** 核对的预约单不存在（GZ-RECYCLE-003，店员核对调出失败） */
    public static final int APPOINTMENT_NOT_FOUND = 4104;
    public static final String APPOINTMENT_NOT_FOUND_MSG = "回收预约单不存在";

    /** 预约单非可核对态（非 submitted；并发已核对 / 已取消 / 已过期，GZ-RECYCLE-003 N8 状态守卫） */
    public static final int NOT_VERIFIABLE = 4105;
    public static final String NOT_VERIFIABLE_MSG = "该预约单当前状态不可核对（可能已确认 / 已取消 / 已过期）";

    /** 触发反向打款时 receiver_openid 缺失（doc/10 §13.E5，确认前兜底） */
    public static final int PAYOUT_OPENID_MISSING = 4106;
    public static final String PAYOUT_OPENID_MISSING_MSG = "用户收款 openid 缺失，无法打款，请用户重新授权";

    private GzRecycleErrorCode() {
    }
}
