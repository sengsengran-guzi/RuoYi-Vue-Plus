package org.dromara.gz.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.user.domain.entity.GzLogisticsAudit;
import org.dromara.gz.user.domain.entity.writable.GzLogisticsOrderRow;
import org.dromara.gz.user.mapper.GzLogisticsAuditMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 物流签收事务原子单元（GZ-USER-104）—— 独立 Spring bean 让 {@code @Transactional} 真正生效。
 *
 * <p><b>为什么单拆一个 bean</b>：自动签收按订单逐单推进，要求「一单失败不阻塞同批」（AC5）且「单订单
 * 条件 UPDATE + 写审计同事务」。若把 {@code @Transactional} 方法放在 {@code GzLogisticsSignServiceImpl}
 * 内由同类 {@code autoSignTable} 直接调用，会触发 Spring AOP <b>自调用失效</b>（不经代理 → 事务注解无效）。
 * 故抽到本 bean，由 service 跨 bean 调用，事务代理正常生效。批级 try-catch 留在 service（本方法抛异常
 * → 单订单事务回滚 → service catch 记 error 继续下一单）。</p>
 *
 * <p>两态并行 + 乐观锁 + 行级幂等（doc/11 §6.4 / D3）：
 * WHERE id=? AND logistics_status='in_china_dispatching' AND version=? →
 * SET logistics_status / business_status='delivered' + delivered_time=NOW() + version+1。
 * 命中（affected=1）才写审计；未命中（已签 / 并发）返 false 不写审计（幂等，AC4）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-104)
 */
@Component
@RequiredArgsConstructor
public class GzLogisticsSignTxHelper {

    private final GzLogisticsAuditMapper auditMapper;

    /**
     * 单订单推进 delivered + 写审计（单事务）。
     *
     * @return true=本次推进成功并写审计 / false=条件未命中（已签 / 并发，幂等跳过）
     */
    @Transactional(rollbackFor = Exception.class)
    public <T extends GzLogisticsOrderRow> boolean markDeliveredAndAudit(
        BaseMapper<T> mapper, Class<T> clazz, Long id, Integer version,
        String orderNo, String businessType, String actionType, String operatorType, String operatorId) {

        int affected = markDeliveredConditionally(mapper, clazz, id, version);
        if (affected == 0) {
            return false;
        }
        writeAudit(businessType, orderNo, actionType, operatorType, operatorId);
        return true;
    }

    private <T extends GzLogisticsOrderRow> int markDeliveredConditionally(
        BaseMapper<T> mapper, Class<T> clazz, Long id, Integer version) {

        LambdaUpdateWrapper<T> uw = new LambdaUpdateWrapper<>(clazz)
            .eq(GzLogisticsOrderRow::getId, id)
            .eq(GzLogisticsOrderRow::getLogisticsStatus, GzLogisticsAudit.STATUS_IN_CHINA_DISPATCHING)
            .eq(GzLogisticsOrderRow::getVersion, version == null ? 0 : version)
            .set(GzLogisticsOrderRow::getLogisticsStatus, GzLogisticsAudit.STATUS_DELIVERED)
            .set(GzLogisticsOrderRow::getBusinessStatus, GzLogisticsAudit.STATUS_DELIVERED)
            .set(GzLogisticsOrderRow::getDeliveredTime, LocalDateTime.now())
            .setSql("version = version + 1");
        return mapper.update(null, uw);
    }

    private void writeAudit(String businessType, String orderNo, String actionType,
                            String operatorType, String operatorId) {
        GzLogisticsAudit audit = new GzLogisticsAudit();
        audit.setBusinessType(businessType);
        audit.setBusinessOrderNo(orderNo);
        audit.setActionType(actionType);
        audit.setFromStatus(GzLogisticsAudit.STATUS_IN_CHINA_DISPATCHING);
        audit.setToStatus(GzLogisticsAudit.STATUS_DELIVERED);
        audit.setOperatorType(operatorType);
        audit.setOperatorId(operatorId);
        audit.setOperatedTime(LocalDateTime.now());
        auditMapper.insert(audit);
    }
}
