package org.dromara.gz.recycle.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.recycle.domain.bo.GzRecycleAppointmentQueryBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleAppointmentSubmitBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleManualHoldBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleRescheduleBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleVerifyBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleVerifyScanBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentAdminVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleWeekBoardVO;
import org.dromara.gz.recycle.domain.vo.RecycleSlotAvailabilityVO;
import org.dromara.gz.recycle.domain.vo.RecycleVerifyCodeVO;

import java.time.LocalDate;
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
     * 提交回收预约（GZ-RECYCLE-007 放开 + ADR-0012 去估价，落 status=submitted）。
     *
     * <p>事务（{@code REPEATABLE_READ}）内：① categories 非空（抛 4108）；② qtyBucketCode 命中启用点数档
     * （抛 4107）→ 取 durationMinutes + occupy_next_slot；③ 用户存在 + receiver_openid（抛 4103）+ 手机号
     * （抛 4125）；④ 到店时段定位本店 enabled 有序列表中的选中档（非法抛 4124）+ 计算下一档；⑤ 时段容量防超卖
     * ——{@code (store,date)} Redis 锁 + {@code FOR UPDATE} 计本档活跃占用（≥1 抛 4122 SLOT_TAKEN），大单再计
     * 下一档（≥1 抛 4123 SLOT_SPILL_BLOCKED）；⑥ product_snapshot_json 落对象（去 IP：ip 字段空）；
     * ⑦ INSERT（去客人拍照/微信号快照；带 time_slot_id + spill_time_slot_id；estimated/total_qty null）。</p>
     *
     * @param bo     提交参数
     * @param userId 当前登录用户 id（sa-token 拿，不接受前端传）
     * @return 提交结果顾客窄 VO（含 appointmentNo / status=submitted，无金额估价）
     */
    GzRecycleAppointmentVO submit(GzRecycleAppointmentSubmitBo bo, Long userId);

    /**
     * 某门店某日<b>小时格</b>可用性（GZ-RECYCLE-012 / ADR-0022，mp 填单选时间用）。
     *
     * <p>逐个 1 小时格标 {@code taken / past / selectable}。占用真源 = 活跃单区间与该格
     * {@code [gi, gi+1h)} 重叠（每格容量 1）。无锁，仅展示 —— 与 submit 的 FOR UPDATE 之间有天然
     * 竞态窗口（UI 显示可约 → 提交拿 4122），这是有意设计。</p>
     *
     * <p><b>{@code qtyBucketCode} 必须收</b>：判定「从这格起放得下 N 小时吗」要综合营业窗口连续性 /
     * 午休不可桥接 / 今日已过 / 逐格占用 / 跨窗口不可连占五条后端知识，让前端拿裸占用自己算必然漂移。
     * 可选：缺省 N=1（保匿名 browse-first）；未知 code 降级 N=1 而<b>不抛 4107</b>。</p>
     *
     * @param storeId       门店 id
     * @param date          到店日期（null → 全格 taken=false）
     * @param qtyBucketCode 点数档 code（可空 → N=1）
     * @return 该门店该日的小时格可用性 + 本次依据的 spanHours
     */
    RecycleSlotAvailabilityVO getSlotAvailability(Long storeId, LocalDate date, String qtyBucketCode);

    /**
     * admin 版小时格可用性（GZ-RECYCLE-012，改期弹窗用）：直接给 {@code spanHours}，并<b>排除某单自身</b>。
     *
     * <p><b>{@code excludeAppointmentId} 必须生效</b>：不排除的话，被改期的单会跟自己的原区间冲突 ——
     * 想把 10:00-14:00 的单挪到 11:00 时，11/12/13 都被自己占着，相邻起点永远选不了。</p>
     *
     * @param storeId               门店 id
     * @param date                  目标日期
     * @param spanHours             本单占用小时数（前端自算：顾客单 ceil(matched/60)，手动占用取区间宽度）
     * @param excludeAppointmentId  排除的单 id（改期单自身；可空）
     */
    RecycleSlotAvailabilityVO getHourSlotsForAdmin(Long storeId, LocalDate date, Integer spanHours,
                                                   Long excludeAppointmentId);

    /**
     * 取消顾客单（GZ-RECYCLE-014，回收看板「取消」动作）：释放它占住的全部小时格。
     *
     * <p>仅 {@code submitted / confirmed_onsite} 可取消 —— 回收是反向打款，
     * {@code paying / paid / payout_failed} 有资金动作在途或已完成，一律 4132。
     * 手动占用请走 {@code releaseHold}（4129）。</p>
     *
     * @param id       预约单 id
     * @param operator 操作人（admin 用户名，写审计列）
     */
    GzRecycleAppointmentAdminVO cancelCustomerAppointment(Long id, String operator);

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

    /**
     * 我当前进行中的回收预约（客户 7.24「一人一单」：回收表单进入前预检）。
     *
     * <p>「进行中」= {@code submitted / confirmed_onsite / paying / payout_failed}（已到账/已取消/已过期释放，可再约）。
     * 有进行中单 → 前端提示 + 禁止再预约；无 → null（可新预约）。取最新一条（create_time desc）。</p>
     *
     * @param userId 当前登录用户 id
     * @return 进行中的预约 VO；无 → null
     */
    GzRecycleAppointmentVO getActiveAppointment(Long userId);

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
     * mp 店员当天回收核对列表（全门店、全状态，不分页，doc/12 §MP-RECYCLE-STAFF-LIST）。
     *
     * <p>店员端「回收核对」列表：某天（{@code appt_date = date}）<b>全部门店、全部状态</b>的回收预约，
     * 复用 {@link #getAdminDetail} 同一套 admin VO 组装（product_snapshot_json 反序列化 + storeName join +
     * 转账段填充）。租户 1001 由 ruoyi 自动注入，不显式过滤。<b>按 slot_start 升序（null 排最后）再按 id 升序</b>；
     * 一天量小，不分页。{@code date} 为 null → 返回空列表（不全表扫）。</p>
     *
     * @param date 到店日期（必传；null → 空列表）
     * @return 当天全门店全状态回收预约 admin VO 列表（slot_start 升序 null last，再 id 升序）
     */
    List<GzRecycleAppointmentAdminVO> listStaffByDate(LocalDate date);

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

    /* ===================== GZ-RECYCLE-010 手动占用时段 + 预约改期 + 周看板（ADR-0021） ===================== */

    /**
     * 手动占用时段（ADR-0021 §1，代客预约 + 临时关闭合一）。
     *
     * <p>独立写入路径，<b>不走 {@link #submit}</b>（一人一单 4127 / openid 4103 / 手机号 4125 / 点数档 4107
     * 校验对手动占用全不适用，坑位 1）。只做「时段合法性 + 容量校验 + INSERT」三步，多格选择 = 多行
     * 同事务 all-or-nothing（任一格 4122/4123/4124 → 整批回滚，不留残行）。{@code user_id} /
     * {@code receiver_openid} / {@code product_snapshot_json} 恒 NULL，{@code create_by} 由 admin
     * 会话自动填。</p>
     *
     * @param bo       手动占用参数（storeId / apptDate / timeSlotIds / remark）
     * @param operator 操作人（admin 用户名，日志用；create_by 由公共字段处理器自动填）
     * @return 新建的手动占用记录（admin VO 列表，每格一条）
     */
    List<GzRecycleAppointmentAdminVO> manualHold(GzRecycleManualHoldBo bo, String operator);

    /**
     * 释放手动占用（ADR-0021 §1 H4）。
     *
     * <p>守卫 {@code source='manual' AND status='manual_hold'}（否则 4129 HOLD_RELEASE_NOT_ALLOWED，
     * 顾客单要走取消流程，不经本方法）→ {@code status='cancelled'} + {@code cancelled_time}，格立即可约。</p>
     *
     * @param id 预约单主键（须为手动占用记录）
     * @return 释放后的记录 VO
     */
    GzRecycleAppointmentAdminVO releaseHold(Long id);

    /**
     * 预约改期——同一行原地 UPDATE（ADR-0021 §2，不取消重建）。
     *
     * <p>适用 {@code submitted}（顾客单）/ {@code manual_hold}（手动占用）；其余状态 4128
     * RESCHEDULE_NOT_ALLOWED。不允许跨门店（{@code store_id} 取原单不变）。容量校验用
     * {@code countActiveHoldingSlotExcludingForUpdate}（排除自身，坑位 4）；spill 按新档位置重算，
     * 改到末档显式置 NULL（坑位 5）。核销码 payload 不含日期/时段，改期后旧码仍有效。</p>
     *
     * @param id       预约单主键
     * @param bo       改期参数（apptDate / timeSlotId，不含 storeId）
     * @param operator 改期操作人（admin 用户名，写 last_reschedule_by）
     * @return 改期后的记录 VO
     */
    GzRecycleAppointmentAdminVO reschedule(Long id, GzRecycleRescheduleBo bo, String operator);

    /**
     * 回收看板周视图（ADR-0021 §3）。
     *
     * <p>{@code weekStart} 后端归一到所在周的周一（传周三也返回周一起 7 天）。一条
     * {@code appt_date BETWEEN weekStart AND weekEnd} 的批量查询后在内存分格（不按 7×N 档循环单查，AC26）。
     * {@code cells} 只返被占格（活跃占用集 = {@code ACTIVE_HOLD_STATUSES}，与防超卖同源）。</p>
     *
     * @param storeId   门店 id
     * @param weekStart 周内任意一天（后端归一到周一）
     * @return 周视图 VO（slots 列头 + cells 被占格）
     */
    GzRecycleWeekBoardVO selectWeekBoard(Long storeId, LocalDate weekStart);
}
