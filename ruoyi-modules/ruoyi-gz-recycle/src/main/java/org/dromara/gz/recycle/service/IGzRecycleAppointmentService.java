package org.dromara.gz.recycle.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.recycle.domain.bo.GzRecycleAppointmentQueryBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleAppointmentSubmitBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleVerifyBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleVerifyScanBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentAdminVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentVO;
import org.dromara.gz.recycle.domain.vo.RecycleVerifyCodeVO;

import java.util.List;

/**
 * 回收预约单服务（ADR-0012，去估价 + 单份多选 + 桶→时长 + 核销码 + 聚合详情）。
 *
 * <p>mp 端能力：① 提交回收预约（单份多选，落 status=submitted，去估价，桶→预计时长）；② 我的回收记录列表 /
 * 详情（顾客窄 VO 三段，含真实到账态）；③ 到店核销码取码（顾客）/ 扫码核对（店员）。confirmed_onsite 起的
 * 店员核对 + 触发反向打款保留（GZ-RECYCLE-003）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
public interface IGzRecycleAppointmentService {

    /**
     * 提交回收预约（ADR-0012 §2，去估价 + 单份多选，落 status=submitted）。
     *
     * <p>事务内：① imageIds 必填二次校验（抛 4101）；② categories 非空（抛 4108）；③ 校验用户存在 +
     * receiver_openid（E5，抛 4103）；④ qtyBucketCode 命中启用桶（抛 4107）→ 取 durationMinutes 落
     * matched_duration_minutes，label 进 snapshot；⑤ ipIds → join 取 ipNames 快照（+ customIps 并存）；
     * ⑥ arrivalSlot → slot_start/slot_end 映射；⑦ product_snapshot_json 落<b>对象</b>；
     * ⑧ estimated_amount_cent/total_qty 置 null（去估价/无精确件数）；⑨ 生成 appointment_no（RCY-）+ INSERT。</p>
     *
     * @param bo     提交参数
     * @param userId 当前登录用户 id（sa-token 拿，不接受前端传）
     * @return 提交结果顾客窄 VO（含 appointmentNo / status=submitted，无金额估价）
     */
    GzRecycleAppointmentVO submit(GzRecycleAppointmentSubmitBo bo, Long userId);

    /**
     * 我的回收记录列表（按 create_time desc，doc/12 §MP-RECYCLE-LIST）。
     *
     * @param userId 当前登录用户 id
     * @return 该用户回收预约列表（含 product 反序列化）
     */
    List<GzRecycleAppointmentVO> selectMyList(Long userId);

    /**
     * 回收预约详情（仅本人，doc/12 §MP-RECYCLE-LIST 点入）。
     *
     * @param id     预约单主键
     * @param userId 当前登录用户 id（越权校验）
     * @return 详情 VO；不存在 / 非本人 → null
     */
    GzRecycleAppointmentVO selectMyDetail(Long id, Long userId);

    /* ===================== GZ-RECYCLE-003 店员核对 + admin 管理 ===================== */

    /**
     * 店员核对调出预约单（GZ-RECYCLE-003 AC1，跨用户：店员看任意用户单）。
     *
     * <p>mp 店员核对页 / admin 详情共用：返回完整 admin VO（两套照片 + 估价 + 快照 + 留痕）。同租户 1001。</p>
     *
     * @param id 预约单主键
     * @return 完整 VO；不存在 → null
     */
    GzRecycleAppointmentAdminVO getAdminDetail(Long id);

    /**
     * 店员核对确认 + 触发反向打款（GZ-RECYCLE-003 AC1+AC2，doc/10 §13.N8/N9，@Transactional）。
     *
     * <p>事务内：① 校验预约单存在 + status=submitted（NOT_VERIFIABLE 守卫）；② version 乐观锁推进
     * submitted→confirmed_onsite + 写核对留痕（verify_image_ids / final_amount_cent / verified_by / verify_time）；
     * ③ 触发反向打款（调 PAY-105 {@code initiatePayout}，business_type=recycle，amount=final_amount_cent，
     * receiver_openid 取快照）→ 回填 out_payout_no + 推进 confirmed_onsite→paying。</p>
     *
     * <p><b>幂等</b>：version 乐观锁防店员重复点确认；PAY-105 内建 1:1 业务单查重防重复转账。paying→paid 的到账回写
     * 由 {@link #syncPayoutResult} 钩子（查单 success）收敛，非本方法同步完成。</p>
     *
     * @param bo         核对参数（appointmentId / verifyImageIds / finalAmountCent）
     * @param verifiedBy 核对店员 admin 用户名（sa-token 取，留痕）
     * @return 核对 + 触发打款后的预约单 VO（status=paying）
     */
    GzRecycleAppointmentAdminVO verifyAndPayout(GzRecycleVerifyBo bo, String verifiedBy);

    /**
     * 失败重试触发打款（GZ-RECYCLE-003 AC3，owner 在 admin 对 payout_failed 单重试，doc/10 §13.E4）。
     *
     * <p>调 PAY-105 {@code retryPayout}（failed→created→重新受理）+ 预约单 payout_failed→paying。
     * 不无限自动重试（仅 owner 手动）。</p>
     *
     * @param id 预约单主键（须 status=payout_failed）
     * @return 重试后的预约单 VO
     */
    GzRecycleAppointmentAdminVO retryPayout(Long id);

    /**
     * paid 回写钩子（GZ-RECYCLE-003 AC2，doc/10 §13.N10）：扫 paying 态预约单 → 查对应 payout 单终态 →
     * 回写 paid（success）/ payout_failed（failed）。paid 时发 {@code CouponIssuanceEvent}（recycle_paid，V1 留位）。
     *
     * <p>由 {@code GzRecyclePayoutSyncJob}（SnailJob）周期调，与 PAY-105 {@code scanAndQuery} 解耦：
     * PAY-105 查单只推进 payout 单本身，回收单 paying→paid 的回写由本钩子按 out_payout_no 关联收敛。
     * 单测可直接调本方法验证闭环（mock payout 单注入 success/failed）。</p>
     *
     * @return 本轮回写 paid 的单数
     */
    int syncPayoutResult();

    /**
     * admin 分页列表（GZ-RECYCLE-003 AC5，按门店 / 日期 / 状态筛）。
     *
     * @param query     查询条件
     * @param pageQuery 分页
     * @return 分页结果（admin VO）
     */
    TableDataInfo<GzRecycleAppointmentAdminVO> selectAdminPage(GzRecycleAppointmentQueryBo query, PageQuery pageQuery);

    /**
     * no_show 凌晨任务兜底（GZ-RECYCLE-003 AC7，doc/10 §13.N12）：扫超 appt_date 仍 submitted 单 → no_show。
     *
     * <p>全租户扫（cron 无登录态）。店员标记优先，本任务兜底未到店未取消的过期单。</p>
     *
     * @return 本轮标记 no_show 的单数
     */
    int markExpiredNoShow();

    /* ===================== T6 到店核销码（契约 15a §F） ===================== */

    /**
     * 取到店核销码（GZ-RECYCLE-004/T6，契约 §F.2，仅本人）。
     *
     * <p>校验本人 + status ∈ {submitted, confirmed_onsite}（否则抛 4109 QR_NOT_AVAILABLE）→ 即时签发
     * （expireEpochSec = now + TTL，不持久化）→ 返 qrPayload + expireEpochSec。</p>
     *
     * @param id     预约单主键
     * @param userId 当前登录用户 id（越权校验，非本人 → 4104）
     * @return 核销码 VO
     */
    RecycleVerifyCodeVO getVerifyCode(Long id, Long userId);

    /**
     * 店员扫码核对定位（GZ-RECYCLE-004/T6，契约 §F.3，核销不限本店）。
     *
     * <p>拆 {@code RC|no|id|exp|code} → 格式校验（4110）→ 过期校验（4111）→ 校签（4112）→ 取单（4104）→ 返全量
     * AdminVO（店员据此进 staff-verify 核对，无门店隔离）。</p>
     *
     * @param bo 扫码参数（qrPayload）
     * @return 定位到的全量 AdminVO
     */
    GzRecycleAppointmentAdminVO verifyScan(GzRecycleVerifyScanBo bo);
}
