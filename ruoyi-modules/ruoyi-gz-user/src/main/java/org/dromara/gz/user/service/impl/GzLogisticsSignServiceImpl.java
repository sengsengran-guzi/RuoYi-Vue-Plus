package org.dromara.gz.user.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.ConfigService;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.user.domain.entity.GzLogisticsAudit;
import org.dromara.gz.user.domain.entity.writable.GzGachaLogisticsRow;
import org.dromara.gz.user.domain.entity.writable.GzLogisticsOrderRow;
import org.dromara.gz.user.domain.entity.writable.GzOrdLogisticsRow;
import org.dromara.gz.user.mapper.writable.GzGachaLogisticsMapper;
import org.dromara.gz.user.mapper.writable.GzOrdLogisticsMapper;
import org.dromara.gz.user.service.IGzLogisticsSignService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨境物流签收服务实现（GZ-USER-104）。
 *
 * <p>两表物流字段同构（doc/11 §6.3 / §7.4），用泛型 helper 复用签收逻辑：
 * preorder → {@link GzOrdLogisticsMapper} / gacha → {@link GzGachaLogisticsMapper}，orderNo 前缀路由。
 * 签收两态并行推进（{@code logistics_status} + {@code business_status} → delivered，doc/11 §6.4）。</p>
 *
 * <p>单订单「条件 UPDATE + 写审计」事务原子性 + 自动签收「一单失败不阻塞同批」由
 * {@link GzLogisticsSignTxHelper}（独立 bean，避免 Spring AOP 自调用失效）承担：本类只做路由 / 归属校验 /
 * 状态校验 / 候选扫描 / 批级 try-catch。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-104)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzLogisticsSignServiceImpl implements IGzLogisticsSignService {

    private static final String PREFIX_PREORDER = "PREORD-";
    private static final String PREFIX_GACHA = "GACHA-";

    /** 自动签收天数配置 key（doc/11 §6.4）；缺失 / 非数字 → 兜底默认 7（强约束 #3） */
    private static final String CONFIG_KEY_AUTO_SIGN_DAYS = "gz.delivery.auto_sign_days";
    private static final int DEFAULT_AUTO_SIGN_DAYS = 7;

    /** cron 单表分页 batch 上限（防长事务，AC5） */
    static final int AUTO_SIGN_BATCH = 100;

    private final GzOrdLogisticsMapper ordLogisticsMapper;
    private final GzGachaLogisticsMapper gachaLogisticsMapper;
    private final GzLogisticsSignTxHelper txHelper;
    private final IGzUserService userService;
    private final ConfigService configService;

    // ============================================================
    //  用户主动确认收货（AC3）
    // ============================================================

    @Override
    public void confirmReceive(String orderNo, Long userId) {
        if (StrUtil.isBlank(orderNo) || userId == null) {
            throw new ServiceException("参数缺失");
        }
        if (orderNo.startsWith(PREFIX_PREORDER)) {
            confirmReceiveTyped(orderNo, userId, GzLogisticsAudit.BIZ_PREORDER,
                ordLogisticsMapper, GzOrdLogisticsRow.class);
        } else if (orderNo.startsWith(PREFIX_GACHA)) {
            confirmReceiveTyped(orderNo, userId, GzLogisticsAudit.BIZ_GACHA,
                gachaLogisticsMapper, GzGachaLogisticsRow.class);
        } else {
            throw new ServiceException("无权操作该订单");
        }
    }

    private <T extends GzLogisticsOrderRow> void confirmReceiveTyped(
        String orderNo, Long userId, String businessType, BaseMapper<T> mapper, Class<T> clazz) {

        T row = mapper.selectOne(new QueryWrapper<T>().eq("order_no", orderNo));
        // 归属校验（AC3 防越权）：不存在 / 非本人 → 统一「无权操作」（不泄露存在性）
        if (row == null || !userId.equals(row.getUserId())) {
            throw new ServiceException("无权操作该订单");
        }
        // 状态校验（AC4）：仅 in_china_dispatching 可签；已 delivered / in_japan → 业务异常
        if (!GzLogisticsAudit.STATUS_IN_CHINA_DISPATCHING.equals(row.getLogisticsStatus())) {
            throw new ServiceException("订单状态不允许此操作");
        }
        String userNo = resolveUserNo(userId);
        boolean ok = txHelper.markDeliveredAndAudit(mapper, clazz, row.getId(), row.getVersion(),
            orderNo, businessType, GzLogisticsAudit.ACTION_USER_CONFIRMED,
            GzLogisticsAudit.OPERATOR_TYPE_USER, userNo);
        if (!ok) {
            // 并发：刚被自动签收 / 另一请求抢先 → 条件未命中，幂等不重写（AC4）
            throw new ServiceException("订单状态不允许此操作");
        }
        log.info("[gz-logistics-sign] user_confirmed orderNo={} userId={} userNo={}", orderNo, userId, userNo);
    }

    // ============================================================
    //  7 天自动签收（AC2，cron 委托）
    // ============================================================

    @Override
    public int autoSign() {
        int days = resolveAutoSignDays();
        LocalDateTime threshold = LocalDateTime.now().minusDays(days);
        int total = 0;
        total += autoSignTable(GzLogisticsAudit.BIZ_PREORDER, ordLogisticsMapper, GzOrdLogisticsRow.class, threshold);
        total += autoSignTable(GzLogisticsAudit.BIZ_GACHA, gachaLogisticsMapper, GzGachaLogisticsRow.class, threshold);
        log.info("[gz-logistics-sign] autoSign done: days={} threshold={} signed={}", days, threshold, total);
        return total;
    }

    /**
     * 扫单表自动签收（AC2/AC5）：查 in_china_dispatching 且 cn_dispatched_at &lt;= threshold 的候选（batch ≤ 100），
     * 逐单 try-catch 委托 {@link GzLogisticsSignTxHelper}（各自独立事务），一单失败不阻塞同批。
     */
    private <T extends GzLogisticsOrderRow> int autoSignTable(
        String businessType, BaseMapper<T> mapper, Class<T> clazz, LocalDateTime threshold) {

        List<T> candidates = mapper.selectList(new LambdaQueryWrapper<>(clazz)
            .eq(GzLogisticsOrderRow::getLogisticsStatus, GzLogisticsAudit.STATUS_IN_CHINA_DISPATCHING)
            .isNotNull(GzLogisticsOrderRow::getCnDispatchedAt)
            .le(GzLogisticsOrderRow::getCnDispatchedAt, threshold)
            .last("LIMIT " + AUTO_SIGN_BATCH));

        int signed = 0;
        for (T row : candidates) {
            try {
                boolean ok = txHelper.markDeliveredAndAudit(mapper, clazz, row.getId(), row.getVersion(),
                    row.getOrderNo(), businessType, GzLogisticsAudit.ACTION_AUTO_DELIVERED,
                    GzLogisticsAudit.OPERATOR_TYPE_SYSTEM, GzLogisticsAudit.OPERATOR_SYSTEM);
                if (ok) {
                    signed++;
                }
            } catch (Exception ex) {
                // 一单失败不阻塞同批其余（AC5），记 error 不抛
                log.error("[gz-logistics-sign] autoSign one fail orderNo={} businessType={}",
                    row.getOrderNo(), businessType, ex);
            }
        }
        return signed;
    }

    // ============================================================
    //  helpers
    // ============================================================

    /** userId → user_no（审计 operator_id，doc/11 §8.3）；查不到回退 userId 字符串（不阻塞签收）。 */
    private String resolveUserNo(Long userId) {
        try {
            GzUserVO vo = userService.selectVoById(userId);
            if (vo != null && StrUtil.isNotBlank(vo.getUserNo())) {
                return vo.getUserNo();
            }
        } catch (Exception ex) {
            log.warn("[gz-logistics-sign] resolve user_no fail userId={}: {}", userId, ex.getMessage());
        }
        return String.valueOf(userId);
    }

    /** 读 sys_config 天数；空 / 非数字 / ≤0 → 兜底默认 7（强约束 #3）。 */
    private int resolveAutoSignDays() {
        String raw = configService.getConfigValue(CONFIG_KEY_AUTO_SIGN_DAYS);
        if (StrUtil.isBlank(raw)) {
            return DEFAULT_AUTO_SIGN_DAYS;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : DEFAULT_AUTO_SIGN_DAYS;
        } catch (NumberFormatException ex) {
            log.warn("[gz-logistics-sign] {} 非数字「{}」，兜底默认 {} 天", CONFIG_KEY_AUTO_SIGN_DAYS, raw, DEFAULT_AUTO_SIGN_DAYS);
            return DEFAULT_AUTO_SIGN_DAYS;
        }
    }
}
