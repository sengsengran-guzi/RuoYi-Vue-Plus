package org.dromara.gz.gacha.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.gacha.domain.bo.GzGachaPrizeBo;
import org.dromara.gz.gacha.domain.bo.GzGachaPrizeQueryBo;
import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.dromara.gz.gacha.domain.vo.GzGachaPrizeVo;

import java.util.List;
import java.util.Map;

/**
 * 奖品池服务（GZ-GACHA-101 admin CRUD）。
 *
 * <p>字段口径权威：doc/11 §7.2。prize_no「查当日最大 + 1」生成；归属机器存在性校验；稀有度枚举校验
 * （SSR/SR/R/N，强约束 #2）；配置态库存/权重非负 + remain≤initial 校验（R2）；软删 del_flag=2。</p>
 *
 * <p><b>并发扣减不在本卡</b>：库存扣减走 GACHA-104 的 SELECT FOR UPDATE + 乐观锁；本卡 service 仅拦
 * 「配置态负库存」，不兜并发（强约束 #5）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
public interface IGzGachaPrizeService {

    /**
     * admin 分页查询（machineId 过滤 + rarity / enabled / name，AC 4 /list）。
     * machineId 为查某机器奖品池的核心入参。
     */
    TableDataInfo<GzGachaPrizeVo> selectAdminPage(GzGachaPrizeQueryBo query, PageQuery pageQuery);

    /**
     * admin 详情（AC 4 /{id}）。不存在返回 null。
     */
    GzGachaPrizeVo selectAdminById(Long id);

    /**
     * 新增奖品（AC 4 POST）。prize_no 系统生成；校验归属机器存在 + 稀有度合法 + 库存/权重非负；
     * stockRemain 为空时默认 = stockInitial。
     *
     * @return 新建奖品主键
     */
    Long insertByBo(GzGachaPrizeBo bo);

    /**
     * 更新奖品（AC 4 PUT）。prize_no / machineId 不在编辑路径改；同新增校验。
     */
    boolean updateByBo(GzGachaPrizeBo bo);

    /**
     * 软删（del_flag=2，AC 4 DELETE）。
     */
    boolean deleteByIds(List<Long> ids);

    /**
     * 某机器奖品总数（机器删除前校验 / 列表 prizeCount 统计用）。
     */
    long countByMachineId(Long machineId);

    /**
     * 某机器全部奖品 entity（mp 详情产品列表 + 抽奖归一化用）。
     *
     * <p>口径：该机器全部未软删奖品（<b>含售罄 / disabled</b>，决策 D2 不后端过滤 —— 前端灰显「已抽完」/
     * 「不参与」）。返回 entity（含 weight / stockRemain / enabled，供 {@code ProbabilityNormalizer.normalizeToPercent}
     * 按入池子集归一化，仅后台抽奖事务用，不对 C 端公示概率 —— ADR-0013）。排序 {@code create_time ASC, id ASC}
     * （稳定顺序，mp 端再按稀有度档位展示）。</p>
     *
     * <p><b>只读</b>（非 FOR UPDATE）：展示场景，与开盒事务的 {@code selectInPoolForUpdate} 锁行无关；
     * 但入池判定共用 {@code ProbabilityNormalizer.isInPool} 同谓词，口径一致。</p>
     *
     * @param machineId 机器主键
     * @return 该机器全部奖品 entity（空机器 → 空列表）
     */
    List<GzGachaPrize> listByMachineId(Long machineId);

    /**
     * 批量统计多台机器「在池奖品总剩余库存」（GZ-GACHA-102 mp 列表 stockRemainSum）。
     *
     * <p>口径：{@code SUM(stock_remain)} WHERE {@code enabled=1}（临停奖品不计）。无奖品 / 全停的机器
     * 在返回 map 中缺省（调用方按 0 处理）。空入参 → 空 map（短路不查库）。</p>
     *
     * @param machineIds 机器主键列表
     * @return machineId → 在池剩余库存合计（缺省 = 0）
     */
    Map<Long, Long> sumStockRemainByMachineIds(List<Long> machineIds);
}
