package org.dromara.gz.recycle.exception;

/**
 * 回收域业务错误码（ADR-0012 / 契约 15a §B.5 / §F）。
 *
 * <p>设计原则同拼豆域 {@code GzBeanErrorCode}：code 为业务可识别整数，mp 端按 R.code 决定 UX；
 * msg 为给用户看的中文（mp 可直接 toast）。ServiceException 体系下 http status 统一 200，按 code 分流。</p>
 *
 * <p><b>码段分配</b>（契约 15a 钉死）：4103 提交校验；4104-4106 店员核对侧；4107 QTY_BUCKET_INVALID /
 * 4108 CATEGORY_REQUIRED 单份提交；4109-4112 到店核销码（§F）。{@code 4101 SUBMIT_IMAGE_REQUIRED 已作废}
 * （GZ-RECYCLE-007 放开：客人不拍照）、{@code 4102 HAS_UNPRICED_CATEGORY 已作废}（去估价 ADR-0012 §1）。
 * 内部资金/重试/时段容量码移至 412x（避开契约 mp 码段）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
public final class GzRecycleErrorCode {

    /** receiver_openid 缺失 / 无效（doc/10 §13.E5），反向打款必需 → 拦截提交 */
    public static final int OPENID_REQUIRED = 4103;
    public static final String OPENID_REQUIRED_MSG = "请重新授权微信登录后再提交回收";

    /** 核对的预约单不存在（店员核对调出失败 / 扫码定位失败） */
    public static final int APPOINTMENT_NOT_FOUND = 4104;
    public static final String APPOINTMENT_NOT_FOUND_MSG = "回收预约单不存在";

    /** 预约单非可核对态（非 submitted；并发已核对 / 已取消 / 已过期，N8 状态守卫） */
    public static final int NOT_VERIFIABLE = 4105;
    public static final String NOT_VERIFIABLE_MSG = "该预约单当前状态不可核对（可能已确认 / 已取消 / 已过期）";

    /** 触发反向打款时 receiver_openid 缺失（doc/10 §13.E5，确认前兜底） */
    public static final int PAYOUT_OPENID_MISSING = 4106;
    public static final String PAYOUT_OPENID_MISSING_MSG = "用户收款 openid 缺失，无法打款，请用户重新授权";

    /** 数量桶无效（qtyBucketCode 未命中启用桶，契约 15a §B.5） */
    public static final int QTY_BUCKET_INVALID = 4107;
    public static final String QTY_BUCKET_INVALID_MSG = "数量区间无效，请重选";

    /** 至少选一个回收品类（categories 空，契约 15a §B.5） */
    public static final int CATEGORY_REQUIRED = 4108;
    public static final String CATEGORY_REQUIRED_MSG = "请至少选择一个回收品类";

    /* ===================== 到店核销码（契约 15a §F） ===================== */

    /** 当前状态不可取核销码（非 submitted/confirmed_onsite，§F.2） */
    public static final int QR_NOT_AVAILABLE = 4109;
    public static final String QR_NOT_AVAILABLE_MSG = "当前状态暂不可生成核销码";

    /** 核销码 payload 格式无法识别（§F.3） */
    public static final int QR_PAYLOAD_MALFORMED = 4110;
    public static final String QR_PAYLOAD_MALFORMED_MSG = "核销码格式无法识别";

    /** 核销码已过期（§F.3） */
    public static final int QR_EXPIRED = 4111;
    public static final String QR_EXPIRED_MSG = "核销码已过期，请让用户刷新后重试";

    /** 核销码签名无效 / 被篡改（§F.3） */
    public static final int QR_SIGNATURE_INVALID = 4112;
    public static final String QR_SIGNATURE_INVALID_MSG = "核销码无效或已被篡改";

    /* ===================== 内部资金 / 重试码（412x，避开契约 mp 码段） ===================== */

    /** 店员核对 final_amount 超绝对硬上限（ADR-0012 §1：去估价后仅留绝对上限防手输多打一位） */
    public static final int FINAL_AMOUNT_EXCEEDS_LIMIT = 4120;
    public static final String FINAL_AMOUNT_EXCEEDS_LIMIT_MSG = "最终金额超出允许上限，请核对后重填或联系管理员";

    /** admin 重试非「打款失败」态的预约单（retry-payout 状态守卫） */
    public static final int RETRY_NOT_ALLOWED = 4121;
    public static final String RETRY_NOT_ALLOWED_MSG = "仅打款失败的预约单可重试";

    /* ============ 小时格容量 / 连占（412x，GZ-RECYCLE-012 / ADR-0022 改小时制） ============ */

    /** 所选起始小时格已被占（每门店每天每小时格仅 1 单，{@code SLOT_CAPACITY}） */
    public static final int SLOT_TAKEN = 4122;
    public static final String SLOT_TAKEN_MSG = "该时间已被预约，请换个时间";

    /**
     * 所需连占的后续小时放不下：被占 / 越出营业时间 / 跨中间休息断档。
     *
     * <p>码号 <b>4123 保持不变</b>（mp 已有分支按码走，改号要动两端）；GZ-RECYCLE-012 只改语义与文案 ——
     * 从「大单溢出占下一档被挡」变为「这个时长从该起点放不下」。</p>
     */
    public static final int SLOT_SPAN_BLOCKED = 4123;
    /** 无小时数上下文时的兜底文案；有 N 时用 {@link #slotSpanBlockedMsg(int)} 出动态文案 */
    public static final String SLOT_SPAN_BLOCKED_MSG = "所选时间放不下本次回收所需时长，请换个时间或减少点数";

    /** 到店时间无效 / 非整点 / 不在营业时间内 */
    public static final int SLOT_INVALID = 4124;
    public static final String SLOT_INVALID_MSG = "到店时间无效或不在营业时间内，请重新选择";

    /** 未提供手机号（放开后：下单需微信登录 + 手机号，doc 需求 #6） */
    public static final int MOBILE_REQUIRED = 4125;
    public static final String MOBILE_REQUIRED_MSG = "请先提供手机号再预约回收";

    /** 同门店同日并发下单抢锁失败（短暂繁忙，可重试） */
    public static final int SLOT_LOCK_BUSY = 4126;
    public static final String SLOT_LOCK_BUSY_MSG = "预约繁忙，请稍后重试";

    /** 一人一单：同用户已有进行中的回收预约（客户 7.24；已到账 paid / 已取消 cancelled / 已过期 no_show 外全挡）。 */
    public static final int ONE_ACTIVE_APPOINTMENT = 4127;
    public static final String ONE_ACTIVE_APPOINTMENT_MSG = "您已有进行中的回收预约，完成或取消后再约";

    /* ===================== 手动占用时段 + 预约改期（412x，ADR-0021） ===================== */

    /** 改期目标单当前状态不可改期（非 submitted/manual_hold，或并发被推进） */
    public static final int RESCHEDULE_NOT_ALLOWED = 4128;
    public static final String RESCHEDULE_NOT_ALLOWED_MSG = "该预约当前状态不可改期";

    /** 仅手动占用记录（source=manual AND status=manual_hold）可在看板释放 */
    public static final int HOLD_RELEASE_NOT_ALLOWED = 4129;
    public static final String HOLD_RELEASE_NOT_ALLOWED_MSG = "仅手动占用记录可在看板释放";

    /**
     * 顾客单改期目标日期早于今天（D21 对抗性测试 F3）：会立刻被 no_show 扫描判过期，顾客的有效预约静默作废。
     * 手动占用（店员台账）不受本限制 —— 落在过去无副作用（cron 不扫 manual_hold、不影响任何未来格）。
     */
    public static final int RESCHEDULE_DATE_PAST = 4130;
    public static final String RESCHEDULE_DATE_PAST_MSG = "顾客预约不能改到已过去的日期，请选择今天或之后的日期";

    /**
     * 所选到店时间已过（今天 + 起始时刻 <= 当前时刻，GZ-RECYCLE-012）。
     *
     * <p>命名档时代一天只有 3 个档，选错概率低；改成 12 个小时格后这是<b>高频</b>误操作。
     * 不拦就会产出一张必然 no_show 的脏单还白占 N 格 —— 而 prod SnailJob 没部署、no_show cron
     * 根本不跑，这些格要靠店员手动取消（GZ-RECYCLE-014）才能放开。</p>
     *
     * <p>手动占用 / 改期<b>豁免</b>：店员回填台账、挪动昨天的占用记录属正常动线（与 4130 现有口径一致）。</p>
     */
    public static final int SLOT_PAST = 4131;
    public static final String SLOT_PAST_MSG = "该时间已过，请选择之后的时间";

    /**
     * 顾客单不可取消（GZ-RECYCLE-014）：仅 {@code submitted / confirmed_onsite} 可取消。
     *
     * <p>回收是<b>反向打款</b>（店家付钱给顾客）：{@code paying / paid / payout_failed} 有资金动作
     * 在途或已完成，取消会让账面与实际打款脱节。手动占用请用 {@code release-hold}（4129）。</p>
     */
    public static final int CANCEL_NOT_ALLOWED = 4132;
    public static final String CANCEL_NOT_ALLOWED_MSG = "该预约已进入打款流程或已结束，不能取消";

    /**
     * 连占放不下的动态文案（GZ-RECYCLE-012）：把「需要几小时」直接告诉用户，
     * 否则「放不下」三个字对用户没有可操作性。
     *
     * @param spanHours 本单需连续占用的小时数
     */
    public static String slotSpanBlockedMsg(int spanHours) {
        return String.format("该点数需连续占用 %d 小时，所选时间放不下，请换个时间或减少点数", spanHours);
    }

    private GzRecycleErrorCode() {
    }
}
