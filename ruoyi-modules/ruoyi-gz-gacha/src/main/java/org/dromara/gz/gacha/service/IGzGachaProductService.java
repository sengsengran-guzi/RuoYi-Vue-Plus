package org.dromara.gz.gacha.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.gacha.domain.bo.GzGachaProductBo;
import org.dromara.gz.gacha.domain.bo.GzGachaProductQueryBo;
import org.dromara.gz.gacha.domain.entity.GzGachaProduct;
import org.dromara.gz.gacha.domain.vo.GzGachaProductVo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 扭蛋产品库服务（ADR-0013 / GZ-GACHA-112 admin CRUD + 投放线/展示 join）。
 *
 * <p>product_no「查当日最大 + 1」生成（GPRD-yyyyMMdd-6位序号，DB UNIQUE 兜底）；删除前引用守卫
 * （被投放线引用拒删）；listOptions 供奖品池选产品下拉（仅 enabled=1）；mapByIds 供投放线/展示
 * 批量 join 取产品（避免 N+1）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-112)
 */
public interface IGzGachaProductService {

    /**
     * admin 分页查询（name 模糊 / ipTag / enabled 精确）。
     */
    TableDataInfo<GzGachaProductVo> selectAdminPage(GzGachaProductQueryBo query, PageQuery pageQuery);

    /**
     * admin 详情（编辑页回填用）。不存在返回 null。
     */
    GzGachaProductVo selectAdminById(Long id);

    /**
     * 新增产品（product_no 系统生成；enabled 为空默认 1）。
     *
     * @return 新建产品主键
     */
    Long insertByBo(GzGachaProductBo bo);

    /**
     * 更新产品（product_no 不在编辑路径改）。
     */
    boolean updateByBo(GzGachaProductBo bo);

    /**
     * 软删（del_flag=2）。<b>引用守卫</b>：产品被任一未删投放线引用 → 抛 ServiceException（先从奖品池移除再删）。
     */
    boolean deleteByIds(List<Long> ids);

    /**
     * 奖品池选产品下拉选项（ADR-0013 §6）：仅 {@code enabled=1}，可选按 ipTag 过滤，按 create_time desc。
     *
     * @param ipTag IP 标签过滤（可空 = 不限）
     * @return 可投放产品列表
     */
    List<GzGachaProductVo> listOptions(String ipTag);

    /**
     * 批量取产品 entity（展示 / 投放线 join 用，避免 N+1）。
     *
     * <p>口径：按 id 集合取（含 enabled=0 / 已被引用的，展示历史投放线需能取到产品名/图）；空入参 → 空 map。
     * 软删产品由 @TableLogic 自动过滤（取不到的 id 在 map 中缺省，调用方按 null 兜底）。</p>
     *
     * @param ids 产品主键集合
     * @return id → 产品 entity（缺省 = 取不到）
     */
    Map<Long, GzGachaProduct> mapByIds(Collection<Long> ids);

    /**
     * 单个产品存在且可投放校验用：取产品 entity（含 enabled，供投放线 insert 校验）。不存在 → null。
     *
     * @param id 产品主键
     * @return 产品 entity（软删 / 跨租户由拦截器过滤 → null）
     */
    GzGachaProduct getById(Long id);
}
