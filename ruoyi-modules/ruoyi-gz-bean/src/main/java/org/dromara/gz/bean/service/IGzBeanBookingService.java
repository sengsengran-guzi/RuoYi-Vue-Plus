package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanBookingQueryBo;
import org.dromara.gz.bean.domain.bo.GzBeanBookingSubmitBo;
import org.dromara.gz.bean.domain.vo.GzBeanBookingMpSubmitVO;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 拼豆预约服务（GZ-BEAN-004）。
 *
 * <p>核心方法：</p>
 * <ul>
 *   <li>{@link #submit} — mp 端提交预约（三层防并发 + 核销码生成）</li>
 *   <li>{@link #selectOccupiedSeatIds} — mp /availability 端点查占用座位（接力 BEAN-003 留位）</li>
 *   <li>{@link #verify} — admin 核销（status pending → used）</li>
 *   <li>{@link #cancel} — 取消预约（mp 用户 / admin 代操作）</li>
 *   <li>{@link #selectMyMpList} — mp 端"我的预约"列表</li>
 *   <li>{@link #selectVoById} — admin / mp 详情</li>
 *   <li>{@link #selectPageList} — admin 列表</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
public interface IGzBeanBookingService {

    /**
     * mp 端提交预约（doc/10 §3.N7）。
     *
     * <p>三层防并发：</p>
     * <ol>
     *   <li>Redis 锁 1：{@code bean_user_submit:{userId}}（TTL 5s）— 防同用户连点</li>
     *   <li>Redis 锁 2：{@code bean_seat:{storeId}:{seatId}:{sessDate}:{slotStart}}（TTL 5s）— 防同座位抢占</li>
     *   <li>DB UNIQUE：{@code uk_dedup_tenant_store_dedup} — 兜底</li>
     * </ol>
     *
     * <p>校验顺序：</p>
     * <ol>
     *   <li>用户手机号已绑定（gz_user.mobile 非空，未绑 → NeedPhoneException）</li>
     *   <li>应用层：同用户同时段同店无其他 pending 预约</li>
     *   <li>INSERT booking with status='pending'（撞 UNIQUE → SeatTakenException）</li>
     *   <li>生成 verifyCode + qrPayload</li>
     *   <li>INSERT booking_log</li>
     * </ol>
     *
     * @param bo     提交参数
     * @param userId 当前登录 user_id（sa-token 拿）
     * @return 提交成功 VO（含 verifyCode + qrPayload）
     */
    GzBeanBookingMpSubmitVO submit(GzBeanBookingSubmitBo bo, Long userId);

    /**
     * 查指定门店 × 日期 × 时段已占用的 seat_id 列表（mp /availability 端点）。
     */
    List<Long> selectOccupiedSeatIds(Long storeId, LocalDate sessDate, LocalTime slotStart);

    /**
     * admin 核销预约（status pending → used，doc/10 §3.N11）。
     *
     * @param bookingId   预约 ID
     * @param verifiedBy  核销操作人（admin username）
     * @return 核销后 VO
     */
    GzBeanBookingVO verify(Long bookingId, String verifiedBy);

    /**
     * 取消预约（status pending → cancelled，doc/10 §3.N9）。
     *
     * <p>方案 C dedup_token 切换：取消时 dedup_token 改为 booking_no（让该座位时段可被别人重新 pending）。</p>
     *
     * @param bookingId    预约 ID
     * @param operatorType "user" / "admin"
     * @param operatorId   操作人（user_id 或 admin username）
     * @return 取消后 VO
     */
    GzBeanBookingVO cancel(Long bookingId, String operatorType, String operatorId);

    /**
     * mp 端"我的预约"列表（按 sessDate desc, slot_start desc）。
     *
     * @param userId 当前用户 ID
     * @param status 可选状态筛选
     */
    List<GzBeanBookingVO> selectMyMpList(Long userId, String status);

    /**
     * 按 ID 查 VO（admin / mp 共用，权限由 Controller 层控制）。
     */
    GzBeanBookingVO selectVoById(Long id);

    /**
     * admin 分页列表。
     */
    TableDataInfo<GzBeanBookingVO> selectPageList(GzBeanBookingQueryBo query, PageQuery pageQuery);
}
