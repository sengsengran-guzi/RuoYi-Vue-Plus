package org.dromara.gz.bean.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.bean.domain.bo.GzBeanAdminCreateBo;
import org.dromara.gz.bean.domain.bo.GzBeanBatchSettleBo;
import org.dromara.gz.bean.domain.bo.GzBeanBookingQueryBo;
import org.dromara.gz.bean.domain.bo.GzBeanBookingVerifyScanBo;
import org.dromara.gz.bean.domain.bo.GzBeanBoardNoteBo;
import org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.mapper.GzAdminUserStoreMapper;
import org.dromara.gz.bean.service.IGzBeanBookingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import org.dromara.gz.bean.domain.vo.GzBeanSeatVO;

import java.util.List;
import java.util.Set;

/**
 * GZ-BEAN-004 admin 端拼豆预约管理。
 *
 * <p>路径前缀 {@code /system/gz/bean/booking}。</p>
 *
 * <p>权限（DDL menu_id 6050-6056）：</p>
 * <ul>
 *   <li>{@code gz:bean:booking:list / query} — owner + staff</li>
 *   <li>{@code gz:bean:booking:verify} — owner + staff（店员核销）</li>
 *   <li>{@code gz:bean:booking:cancel} — 仅 owner（admin 代取消）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/bean/booking")
public class GzBeanBookingController extends BaseController {

    /** owner / 超管 角色 key（看全部门店，与 GZ-SYS-006 口径一致） */
    private static final Set<String> ALL_STORE_ROLES = Set.of("owner", "superadmin");

    private final IGzBeanBookingService bookingService;
    private final GzAdminUserStoreMapper adminUserStoreMapper;

    /**
     * 分页列表（GZ-BEAN-008 store_id 权限隔离）。
     *
     * <p>owner / superadmin → 看全部（staffStoreId=null，受 query.storeId 可选筛选）；
     * staff → 强制只看自己绑定门店（staffStoreId=gz_store_id，忽略 query.storeId）。</p>
     */
    @SaCheckPermission("gz:bean:booking:list")
    @GetMapping("/list")
    public TableDataInfo<GzBeanBookingVO> list(GzBeanBookingQueryBo query, PageQuery pageQuery) {
        Long staffStoreId = resolveStaffStoreId();
        return bookingService.selectPageList(query, pageQuery, staffStoreId);
    }

    /** 详情 */
    @SaCheckPermission("gz:bean:booking:query")
    @GetMapping("/{id}")
    public R<GzBeanBookingVO> detail(@PathVariable Long id) {
        GzBeanBookingVO vo = bookingService.selectVoById(id);
        if (vo == null) {
            return R.fail("预约不存在");
        }
        return R.ok(vo);
    }

    /**
     * 手动核销 + 现场分座（列表 / 看板选 pending 行 → 分配空闲座 → 二次确认，status pending → used）。
     * {@code seatId} = 店员现场分配的物理座位（ADR-0016 §3）；新模型单必传，存量已绑座单可省。
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @Log(title = "拼豆预约核销", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/verify")
    public R<GzBeanBookingVO> verify(@PathVariable Long id, @RequestParam(required = false) Long seatId) {
        String adminUsername = LoginHelper.getUsername();
        log.info("[bean-booking-admin] verify id={} seatId={} by={}", id, seatId, adminUsername);
        return R.ok(bookingService.verify(id, seatId, adminUsername));
    }

    /**
     * 某预约核销分座时<b>可分配的空闲座</b>（ADR-0016 §3）：本店 + 该预约桌型 + 启用，且排除该日该时段
     * 已被占用 / 按星期关闭的座。核销弹窗座位下拉据此只列「点了不报 SEAT_TAKEN」的座，避免店员撞占。
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @GetMapping("/{id}/assignable-seats")
    public R<List<GzBeanSeatVO>> assignableSeats(@PathVariable Long id) {
        return R.ok(bookingService.selectAssignableSeats(id));
    }

    /**
     * 扫码核销（GZ-BEAN-008 AC4）。
     *
     * <p>admin PC 端上传 QR 截图 → jsqr 浏览器解码 → 拿 payload 调本端点 →
     * 后端解析 {@code "BK|{bookingNo}|{verifyCode}"} → 校签 → 复用手动核销底层。</p>
     *
     * <p>复用 {@code gz:bean:booking:verify} 权限（owner + staff 均可，无需新权限点）。</p>
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @Log(title = "拼豆预约扫码核销", businessType = BusinessType.UPDATE)
    @PostMapping("/verify-scan")
    public R<GzBeanBookingVO> verifyScan(@Validated @RequestBody GzBeanBookingVerifyScanBo bo) {
        String adminUsername = LoginHelper.getUsername();
        log.info("[bean-booking-admin] verify-scan by={} seatId={} payloadLen={}",
            adminUsername, bo.getSeatId(), bo.getQrPayload() == null ? 0 : bo.getQrPayload().length());
        return R.ok(bookingService.verifyByQrPayload(bo.getQrPayload(), bo.getSeatId(), adminUsername));
    }

    /** admin 代取消（status pending → cancelled） */
    @SaCheckPermission("gz:bean:booking:cancel")
    @Log(title = "拼豆预约取消", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/cancel")
    public R<GzBeanBookingVO> cancel(@PathVariable Long id) {
        String adminUsername = LoginHelper.getUsername();
        log.info("[bean-booking-admin] cancel id={} by={}", id, adminUsername);
        return R.ok(bookingService.cancel(id, "admin", adminUsername));
    }

    // ============================================================
    //  GZ-BEAN-026 店内计时看板（ADR-0015 §5 / doc/11 §3.12 / doc/10 §11 看板子流程）
    //  看板查询 + 提前放座 + 延时 —— owner / 店员现场操作，复用 booking:verify 权限
    //  （店员核销权限语义最接近；独立权限点 gz:bean:board:* 待 GZ-BEAN-028 admin 看板 menu seed 拆分）
    // ============================================================

    /**
     * 店内计时看板（GZ-BEAN-026）：某门店某日各启用座位单元实时状态行。
     *
     * <p>店员到店看「哪个座位还有多久结束」。owner/superadmin 看全部门店，store_id 自选；staff 绑定门店时
     * 由 plus-ui 传其门店 storeId（看板按 storeId 维度查，本端点不做强隔离 —— V1.0 多店放开口径，
     * staff 误传他店仅是看别店看板，无写操作越权风险）。</p>
     *
     * @param storeId  门店 ID（必填）
     * @param sessDate 看板日期（必填，默认前端传当日）
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @GetMapping("/board")
    public R<List<GzBeanBoardRowVO>> board(@RequestParam Long storeId,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sessDate) {
        return R.ok(bookingService.selectBoard(storeId, sessDate));
    }

    /**
     * 看板②待分座区（ADR-0016 §3/§5/§6）：某门店某日已付款待核销但<b>尚未分配物理座位</b>的预约列表
     * （{@code seat_id IS NULL AND status=pending AND pay_status=paid}）。店员从本列表挑一笔 → 给它分一个
     * ①区空闲座（{@code POST /{id}/verify?seatId=}）完成核销分座。下单选桌型模型下（ADR-0016 §1）所有新单核销前都在此。
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @GetMapping("/board/pending-assign")
    public R<List<GzBeanBookingVO>> boardPendingAssign(
            @RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sessDate) {
        return R.ok(bookingService.selectPendingAssignList(storeId, sessDate));
    }

    /**
     * 提前放座（GZ-BEAN-026）：对某在店使用中（used）单写 actual_end_time/slot，该座剩余格立即可再约。
     * 不改 status（仍 used）。幂等：已放座单重复点无害（返当前看板行）。
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @Log(title = "拼豆看板提前放座", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/release-seat")
    public R<GzBeanBoardRowVO> releaseSeat(@PathVariable Long id) {
        String adminUsername = LoginHelper.getUsername();
        log.info("[bean-board-admin] releaseSeat id={} by={}", id, adminUsername);
        return R.ok(bookingService.releaseSeatEarly(id, adminUsername));
    }

    /**
     * 延时（GZ-BEAN-026 / kevin-test §3a）：把某在店使用中（used）单的 slot_end 往后推 addMinutes <b>分钟</b>。
     * slot_end 精确到分（更好记录，差额线下结算）；占用按整点格回收（延时溢入下一格即占该格、mp 余量 −1）。
     * 先按具体座位区间互斥校验新增格未被占（占了拒绝 EXTEND_CONFLICT）。V1 不线上补付。
     *
     * @param id         预约 ID
     * @param addMinutes 延后分钟数（正整数 1-720）
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @Log(title = "拼豆看板延时", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/extend")
    public R<GzBeanBoardRowVO> extend(@PathVariable Long id, @RequestParam int addMinutes) {
        String adminUsername = LoginHelper.getUsername();
        log.info("[bean-board-admin] extend id={} addMinutes={} by={}", id, addMinutes, adminUsername);
        return R.ok(bookingService.extendBooking(id, addMinutes, adminUsername));
    }

    /**
     * 改派座位（GZ-BEAN-040 / kevin-test §5）：把某已核销（used 未放座）单改派到另一空闲座位
     * （店员核销时分错座的补救）。复用核销分座校验链（本店/启用/桌型匹配/关闭/区间互斥）。
     *
     * @param id        预约 ID
     * @param seatId    改派到的目标空闲座位 id
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @Log(title = "拼豆看板改派座位", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/reassign-seat")
    public R<GzBeanBoardRowVO> reassignSeat(@PathVariable Long id, @RequestParam Long seatId) {
        String adminUsername = LoginHelper.getUsername();
        log.info("[bean-board-admin] reassignSeat id={} newSeatId={} by={}", id, seatId, adminUsername);
        return R.ok(bookingService.reassignSeat(id, seatId, adminUsername));
    }

    /**
     * 看板备注：店员点看板某座位 → 记一条备注。按占用状态分两处存 —— 座位占用中传 {@code bo.bookingId}
     * （挂本次占用单 board_note，放座后看板不再展示）；座位空闲不传 bookingId（挂 gz_bean_seat.remark，长期留存）。
     * {@code bo.remark} 传空/空串 = 清空（删除）。复用 {@code gz:bean:booking:verify} 权限（店员可写）。
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @Log(title = "拼豆看板备注", businessType = BusinessType.UPDATE)
    @PutMapping("/board/seat/{seatId}/note")
    public R<Void> updateBoardNote(@PathVariable Long seatId, @Validated @RequestBody GzBeanBoardNoteBo bo) {
        String adminUsername = LoginHelper.getUsername();
        log.info("[bean-board-admin] updateBoardNote seatId={} bookingId={} by={}", seatId, bo.getBookingId(), adminUsername);
        bookingService.updateBoardNote(seatId, bo.getBookingId(), bo.getRemark(), adminUsername);
        return R.ok();
    }

    /**
     * admin 代客预定（GZ-BEAN-039 / kevin-test §4）：现场散客，店员代建单（线下已付），
     * 建成 pending 待分座 —— 不在此选座，座位留到核销时现场分（ADR-0016 §3）。走逐格配额防超卖。
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @Log(title = "拼豆代客预定", businessType = BusinessType.INSERT)
    @PostMapping("/admin-create")
    public R<GzBeanBookingVO> adminCreate(@Validated @RequestBody GzBeanAdminCreateBo bo) {
        String adminUsername = LoginHelper.getUsername();
        log.info("[bean-admin-create] storeId={} type={} slot={}-{} by={}",
            bo.getStoreId(), bo.getSeatTypeConfigId(), bo.getSlotStart(), bo.getSlotEnd(), adminUsername);
        return R.ok(bookingService.adminCreateBooking(bo, adminUsername));
    }

    // ============================================================
    //  GZ-BEAN-041 看板过期单批量结单 / 补核销（kevin-test §6）
    // ============================================================

    /**
     * 看板「过期待处理」区（GZ-BEAN-041）：某门店某日时段已过仍未终结的单
     * （待分座过期 pending / 已被 cron 扫走的 no_show / 已超时 used 未放座），供批量结单。
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @GetMapping("/board/expired-unsettled")
    public R<List<GzBeanBookingVO>> boardExpiredUnsettled(
            @RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sessDate) {
        return R.ok(bookingService.selectExpiredUnsettled(storeId, sessDate));
    }

    /**
     * 批量结单（GZ-BEAN-041）：对一批过期单按 action 统一处理 —— completed（补核销为已完成，
     * pending|no_show → used，无座历史结算）/ no_show（标爽约）/ released（已超时 used 标已结束 = 放座）。
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @Log(title = "拼豆看板批量结单", businessType = BusinessType.UPDATE)
    @PostMapping("/batch-settle")
    public R<IGzBeanBookingService.BatchSettleResult> batchSettle(@Validated @RequestBody GzBeanBatchSettleBo bo) {
        String adminUsername = LoginHelper.getUsername();
        log.info("[bean-board-admin] batchSettle action={} count={} by={}",
            bo.getAction(), bo.getBookingIds() == null ? 0 : bo.getBookingIds().size(), adminUsername);
        return R.ok(bookingService.batchSettle(bo.getBookingIds(), bo.getAction(), adminUsername));
    }

    /* ============ private ============ */

    /**
     * 解析当前登录 admin 的门店隔离 id（GZ-BEAN-008 强约束 #6）。
     *
     * <p>owner / superadmin → 返 null（看全部）；staff → 返其 sys_user.gz_store_id（仅看本店）。
     * staff 未绑门店（gz_store_id 为 null）时同样返 null —— 与 GZ-SYS-006 staff scope 同思路，
     * V1.0 单店场景下 staff 绑定后才有隔离意义；未绑定不做额外拦截（owner 在 ADMIN-002 UI 上配）。</p>
     */
    private Long resolveStaffStoreId() {
        LoginUser user = LoginHelper.getLoginUser();
        if (user == null) {
            return null;
        }
        Set<String> roles = user.getRolePermission();
        if (roles != null) {
            for (String role : roles) {
                if (ALL_STORE_ROLES.contains(role)) {
                    return null;
                }
            }
        }
        // staff（非 owner/superadmin）→ 取绑定门店
        return adminUserStoreMapper.selectStoreIdByUserId(user.getUserId());
    }
}
