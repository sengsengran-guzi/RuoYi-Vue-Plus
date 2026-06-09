package org.dromara.gz.gacha.service.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.gacha.domain.entity.GzGachaMachine;
import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.dromara.gz.gacha.enums.GachaMachineStatusEnum;
import org.dromara.gz.gacha.mapper.GzGachaMachineMapper;
import org.dromara.gz.gacha.mapper.GzGachaPrizeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 整机售罄防御性自动下架（GZ-GACHA-104 AC7 / 决策 D6-D7）。
 *
 * <p><b>独立 Bean</b>：开盒事务提交后（after-commit 回调）由 {@code GzGachaDrawServiceImpl} 跨 Bean 调用本服务
 * （非自调用，保证 {@code @Transactional REQUIRES_NEW} 真正生效，独立事务，不延长开盒事务对 prize 行锁的持有）。</p>
 *
 * <p><b>检测</b>：整机所有奖品 {@code enabled=1 AND stock_remain>0} 计数为 0 → 机器 {@code on_shelf} 置
 * {@code auto_off}（禁 off_shelf / archived，强约束 #7）。运营保证整机不全空，此为防御性；<b>无用户侧退款</b>。
 * auto_off 后不推 mp 端（决策 D7：用户下次进列表 GACHA-102 已过滤 on_shelf 自然看不到，不引 ws）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GachaMachineAutoOffService {

    private final GzGachaMachineMapper machineMapper;
    private final GzGachaPrizeMapper prizeMapper;

    /**
     * 检测整机售罄 → auto_off（防御性，独立事务）。失败不抛（不影响已成功的开盒；运营 / 下次开盒 N4 兜底）。
     *
     * @param machineId 机器 id
     */
    @Transactional(rollbackFor = Exception.class)
    public void autoOffIfEmpty(Long machineId) {
        try {
            long remainAvailable = prizeMapper.selectCount(Wrappers.<GzGachaPrize>lambdaQuery()
                .eq(GzGachaPrize::getMachineId, machineId)
                .eq(GzGachaPrize::getEnabled, 1)
                .gt(GzGachaPrize::getStockRemain, 0));
            if (remainAvailable > 0) {
                return;
            }
            GzGachaMachine machine = machineMapper.selectById(machineId);
            if (machine != null && GachaMachineStatusEnum.ON_SHELF.getCode().equals(machine.getStatus())) {
                machine.setStatus(GachaMachineStatusEnum.AUTO_OFF.getCode());
                machineMapper.updateById(machine);
                log.warn("[gz-gacha-autooff] 整机售罄防御性自动下架 machineId={} → auto_off（运营应保证补货，无用户侧退款）", machineId);
            }
        } catch (Exception e) {
            log.error("[gz-gacha-autooff] auto_off 检测失败 machineId={}（不影响开盒结果）", machineId, e);
        }
    }
}
