package org.dromara.gz.ord.service.impl;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.ord.domain.dto.LogisticsCarrierUpdateDto;
import org.dromara.gz.ord.domain.dto.LogisticsForwardDto;
import org.dromara.gz.ord.domain.dto.LogisticsRollbackDto;
import org.dromara.gz.ord.domain.vo.LogisticsOrderStateVo;
import org.dromara.gz.ord.enums.ExpressCarrierEnum;
import org.dromara.gz.ord.enums.LogisticsStatusEnum;
import org.dromara.gz.ord.mapper.GzLogisticsMapper;
import org.dromara.gz.ord.service.IGzLogisticsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨境物流 2 态推进 service 实现（GZ-ADMIN-104）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-104)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzLogisticsServiceImpl implements IGzLogisticsService {

    private static final String BT_PREORDER = "preorder";
    private static final String BT_GACHA = "gacha";

    private static final String S_IN_JAPAN = LogisticsStatusEnum.IN_JAPAN.getCode();
    private static final String S_DISPATCHING = LogisticsStatusEnum.IN_CHINA_DISPATCHING.getCode();
    private static final String S_DELIVERED = LogisticsStatusEnum.DELIVERED.getCode();

    private static final String OPERATOR_TYPE_ADMIN = "admin";

    private final GzLogisticsMapper logisticsMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forward(LogisticsForwardDto dto) {
        String table = tableOf(dto.getBusinessType());
        LogisticsOrderStateVo state = requireState(table, dto.getBusinessOrderNo());
        assertBusinessStatusPushable(state);

        String cur = StrUtil.blankToDefault(state.getLogisticsStatus(), S_IN_JAPAN);
        if (S_IN_JAPAN.equals(cur)) {
            // in_japan → in_china_dispatching：必录快递 + 单号（doc/10 §9.E5 / N2）
            String carrier = dto.getCnCarrierCode();
            String tracking = dto.getCnTrackingNo();
            if (StrUtil.isBlank(carrier) || StrUtil.isBlank(tracking)) {
                throw new ServiceException("进『已发往中国』必须录快递公司和单号");
            }
            if (!ExpressCarrierEnum.isValid(carrier)) {
                throw new ServiceException("快递公司编码非法（须为 9 项之一）: " + carrier);
            }
            int n = logisticsMapper.forwardToDispatching(table, dto.getBusinessOrderNo(), carrier, tracking);
            if (n == 0) {
                throw new ServiceException("推进失败：订单状态已变更，请刷新重试");
            }
            writeAudit(dto.getBusinessType(), dto.getBusinessOrderNo(), "status_forward",
                S_IN_JAPAN, S_DISPATCHING, null, null, carrier, tracking, null);
            log.info("[GZ-LOGISTICS] forward {} {} in_japan→in_china_dispatching carrier={} tracking={}",
                dto.getBusinessType(), dto.getBusinessOrderNo(), carrier, tracking);
        } else if (S_DISPATCHING.equals(cur)) {
            // in_china_dispatching → delivered
            int n = logisticsMapper.forwardToDelivered(table, dto.getBusinessOrderNo());
            if (n == 0) {
                throw new ServiceException("推进失败：订单状态已变更，请刷新重试");
            }
            writeAudit(dto.getBusinessType(), dto.getBusinessOrderNo(), "status_forward",
                S_DISPATCHING, S_DELIVERED, null, null, null, null, null);
            log.info("[GZ-LOGISTICS] forward {} {} in_china_dispatching→delivered",
                dto.getBusinessType(), dto.getBusinessOrderNo());
        } else if (S_DELIVERED.equals(cur)) {
            throw new ServiceException("订单已签收，无法继续推进物流");
        } else {
            throw new ServiceException("未知物流状态: " + cur);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCarrier(LogisticsCarrierUpdateDto dto) {
        String table = tableOf(dto.getBusinessType());
        LogisticsOrderStateVo state = requireState(table, dto.getBusinessOrderNo());
        if (!S_DISPATCHING.equals(state.getLogisticsStatus())) {
            throw new ServiceException("订单不在『国内派送中』，不可改单号");
        }
        if (!ExpressCarrierEnum.isValid(dto.getCnCarrierCode())) {
            throw new ServiceException("快递公司编码非法（须为 9 项之一）: " + dto.getCnCarrierCode());
        }
        int n = logisticsMapper.updateCarrier(table, dto.getBusinessOrderNo(), dto.getCnCarrierCode(), dto.getCnTrackingNo());
        if (n == 0) {
            throw new ServiceException("改单号失败：订单状态已变更，请刷新重试");
        }
        // carrier_update：记 from/to 单号（不禁止修改，doc/10 §9.N9 / E2）
        writeAudit(dto.getBusinessType(), dto.getBusinessOrderNo(), "carrier_update",
            null, null, state.getCnCarrierCode(), state.getCnTrackingNo(),
            dto.getCnCarrierCode(), dto.getCnTrackingNo(), null);
        log.info("[GZ-LOGISTICS] carrier_update {} {} {}/{} → {}/{}",
            dto.getBusinessType(), dto.getBusinessOrderNo(),
            state.getCnCarrierCode(), state.getCnTrackingNo(), dto.getCnCarrierCode(), dto.getCnTrackingNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollback(LogisticsRollbackDto dto) {
        String table = tableOf(dto.getBusinessType());
        LogisticsOrderStateVo state = requireState(table, dto.getBusinessOrderNo());
        String cur = StrUtil.blankToDefault(state.getLogisticsStatus(), S_IN_JAPAN);
        String prev = prevOf(cur);
        if (prev == null) {
            throw new ServiceException("订单处于初始态『在日本』，无法回退");
        }
        int n = logisticsMapper.rollback(table, dto.getBusinessOrderNo(), cur, prev);
        if (n == 0) {
            throw new ServiceException("回退失败：订单状态已变更，请刷新重试");
        }
        writeAudit(dto.getBusinessType(), dto.getBusinessOrderNo(), "status_rollback",
            cur, prev, null, null, null, null, dto.getReason());
        log.info("[GZ-LOGISTICS] rollback {} {} {}→{} reason={}",
            dto.getBusinessType(), dto.getBusinessOrderNo(), cur, prev, dto.getReason());
    }

    // --------------------------------------------------------------------

    private String tableOf(String businessType) {
        if (BT_PREORDER.equals(businessType)) {
            return "gz_ord_order";
        }
        if (BT_GACHA.equals(businessType)) {
            return "gz_gacha_order";
        }
        throw new ServiceException("不支持的业务类型（仅 preorder/gacha 有物流）: " + businessType);
    }

    private LogisticsOrderStateVo requireState(String table, String orderNo) {
        LogisticsOrderStateVo state = logisticsMapper.selectState(table, orderNo);
        if (state == null) {
            throw new ServiceException("订单不存在: " + orderNo);
        }
        return state;
    }

    /** E6：退款订单不可推进物流（logistics 与 business 并行，但 refunded 拦截推进，doc/10 §9.E3/E6）。 */
    private void assertBusinessStatusPushable(LogisticsOrderStateVo state) {
        if ("refunded".equals(state.getBusinessStatus())) {
            throw new ServiceException("该订单已退款，当前状态不可推进物流");
        }
    }

    /** 上一态（回退用）：delivered→dispatching→in_japan→null。 */
    private String prevOf(String cur) {
        if (S_DELIVERED.equals(cur)) {
            return S_DISPATCHING;
        }
        if (S_DISPATCHING.equals(cur)) {
            return S_IN_JAPAN;
        }
        return null;
    }

    private void writeAudit(String businessType, String businessOrderNo, String actionType,
                            String fromStatus, String toStatus,
                            String fromCarrier, String fromTracking, String toCarrier, String toTracking,
                            String reason) {
        logisticsMapper.insertAudit(businessType, businessOrderNo, actionType,
            fromStatus, toStatus, fromCarrier, fromTracking, toCarrier, toTracking,
            reason, OPERATOR_TYPE_ADMIN, LoginHelper.getUsername(), LoginHelper.getTenantId());
    }
}
