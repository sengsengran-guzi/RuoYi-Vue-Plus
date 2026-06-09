package org.dromara.gz.gacha.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.gacha.domain.bo.GzGachaMachineBo;
import org.dromara.gz.gacha.domain.bo.GzGachaMachineQueryBo;
import org.dromara.gz.gacha.domain.vo.GzGachaMachineDetailVo;
import org.dromara.gz.gacha.domain.vo.GzGachaMachineMpVo;
import org.dromara.gz.gacha.domain.vo.GzGachaMachineVo;

import java.util.List;

/**
 * 扭蛋机服务（GZ-GACHA-101 admin CRUD）。
 *
 * <p>字段口径权威：doc/11 §7.1。machine_no「查当日最大 + 1」生成（同 article_no / product_no 模式）；
 * 状态流转走 {@link #changeStatus}（on_shelf ↔ off_shelf，auto_off 仅 GACHA-104/cron）；软删 del_flag=2
 * （仍有奖品时拒删）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
public interface IGzGachaMachineService {

    /**
     * admin 分页查询（status / ipTag / name 筛选，AC 4 /list）。含奖品池奖品数 prizeCount。
     */
    TableDataInfo<GzGachaMachineVo> selectAdminPage(GzGachaMachineQueryBo query, PageQuery pageQuery);

    /**
     * admin 详情（AC 4 /{id}）。不存在返回 null。
     */
    GzGachaMachineVo selectAdminById(Long id);

    /**
     * 新增扭蛋机（AC 4 POST）。machine_no 系统生成；status 固定 off_shelf。
     *
     * @return 新建机器主键
     */
    Long insertByBo(GzGachaMachineBo bo);

    /**
     * 更新扭蛋机（AC 4 PUT）。machine_no / status / salesCount 不在编辑路径改（status 走 changeStatus）。
     */
    boolean updateByBo(GzGachaMachineBo bo);

    /**
     * 手动上下架（AC 4）。仅 on_shelf ↔ off_shelf；targetStatus=auto_off 拒绝
     * （决策 D5，auto_off 仅 GACHA-104/cron 写，抛 INVALID_MACHINE_STATUS）。
     */
    boolean changeStatus(Long id, String targetStatus);

    /**
     * 软删（del_flag=2，AC 4 DELETE）。机器仍有奖品时拒删（抛 MACHINE_HAS_PRIZE，先清空奖品池）。
     */
    boolean deleteByIds(List<Long> ids);

    // ============================================================
    //  GZ-GACHA-102 — mp 端 C 端浏览（在售扭蛋机列表）
    // ============================================================

    /**
     * mp 在售扭蛋机分页列表（GZ-GACHA-102 AC1）。
     *
     * <p>仅返回 {@code status='on_shelf'} + {@code del_flag=0} 的机器（排除 off_shelf / auto_off）；
     * 排序 {@code online_time DESC, id DESC}（doc/11 §7.1 无 sort_order 字段，决策 D3）；分页 PageQuery。
     * 每张卡片含 {@code coverImageUrl}（cover_image_id 解析）+ {@code stockRemainSum}
     * （SUM(prize.stock_remain) WHERE enabled=1，批量查避 N+1）。</p>
     *
     * @param pageQuery 分页参数（pageNum / pageSize）
     * @return 在售机器卡片分页（TableDataInfo rows + total；http 拦截器识别 rows/total）
     */
    TableDataInfo<GzGachaMachineMpVo> listOnShelfForMp(PageQuery pageQuery);

    // ============================================================
    //  GZ-GACHA-103 — mp 单机详情 + 概率公示（实时归一化）
    // ============================================================

    /**
     * mp 单机详情 + 概率公示（GZ-GACHA-103 AC1，doc/10 §8.N2/N3）。
     *
     * <p>返回机器主体 + 该机器<b>全部</b>奖品（含售罄 / disabled，决策 D2 不后端过滤）+ 每条
     * {@code normalizedProbability}（service 层算并返回，前端不重算）。归一化复用
     * {@code ProbabilityNormalizer.normalizeToPercent}（与开盒事务 GACHA-104 同口径，强约束 #2/#3）：
     * 入池子集 {@code enabled=1 AND stock_remain>0} 的 weight 归一化；不在池 → {@code null}（前端 "—"）。</p>
     *
     * <p><b>不限机器状态</b>：详情页对 off_shelf / auto_off 机器也可查看（列表只展示 on_shelf，但直链 /
     * 收藏可达非在售机器）；前端按 status + 入池为空判定置灰 CTA。机器不存在 → 抛 MACHINE_NOT_FOUND。</p>
     *
     * @param machineId 机器主键（路由 string，controller parse Long）
     * @return 机器详情 + 奖品池 + 实时归一化概率
     */
    GzGachaMachineDetailVo getDetailForMp(Long machineId);
}
