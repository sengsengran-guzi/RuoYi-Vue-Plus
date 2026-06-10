package org.dromara.gz.recycle.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.recycle.domain.bo.GzRecycleAppointmentQueryBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleAppointmentSubmitBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleVerifyBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentAdminVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleEstimateAllVO;

import java.util.List;

/**
 * 回收预约单服务（GZ-RECYCLE-002，doc/10 §13 / doc/11 §12.2）。
 *
 * <p>本卡 mp 端能力：① 多品类实时累加估价（试算，不落库）；② 提交回收预约（落 status=submitted，
 * 估价 / 时长 / 快照冻结）；③ 我的回收记录列表 / 详情（只读壳）。confirmed_onsite 起的店员核对 +
 * 触发反向打款在 GZ-RECYCLE-003。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-002)
 */
public interface IGzRecycleAppointmentService {

    /**
     * 多品类累加估价试算（doc/11 F12.1，mp 填单实时展示，不落库）。
     *
     * <p>对每条 {@code (category, qty)} 调价目表单品类 estimate 后累加：
     * {@code estimatedAmountCent = Σ}、{@code totalQty = Σ qty}、{@code matchedDurationMinutes = Σ 命中时长}。
     * 某品类未命中区间（E1）→ 该行 priced=false 不阻断其余；hasUnpriced=true 时整单不可提交。</p>
     *
     * @param products 物品清单（品类 + 数量）
     * @return 累加估价结果 + 逐品类明细
     */
    GzRecycleEstimateAllVO estimateAll(List<GzRecycleAppointmentSubmitBo.ProductLine> products);

    /**
     * 提交回收预约（doc/10 §13.N5，落 status=submitted）。
     *
     * <p>事务内：① 校验用户存在 + receiver_openid（E5）；② 后端重算估价 + 时长 + total_qty 冻结（不信前端金额）；
     * ③ 含未估价品类（E1）拒收；④ submit_image_ids 必填二次校验（AC3，E7）；⑤ snapshot openid/mobile/wechatId；
     * ⑥ 生成 appointment_no（RCY-）+ INSERT。</p>
     *
     * @param bo     提交参数
     * @param userId 当前登录用户 id（sa-token 拿，不接受前端传）
     * @return 提交结果 VO（含 appointmentNo + 冻结估价）
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
}
