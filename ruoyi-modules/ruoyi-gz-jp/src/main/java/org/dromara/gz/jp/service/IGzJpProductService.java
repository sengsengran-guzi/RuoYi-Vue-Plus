package org.dromara.gz.jp.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.jp.domain.bo.GzJpProductBo;
import org.dromara.gz.jp.domain.bo.GzJpProductQueryBo;
import org.dromara.gz.jp.domain.bo.GzJpProductStatusBo;
import org.dromara.gz.jp.domain.vo.GzJpProductAdminVO;

import java.util.List;

/**
 * 拼团商品服务（GZ-JP-102，FLOW:F-JP-01.step2「店员在场内逐个上架商品」）。
 *
 * <p>本 ticket 只覆盖 <b>admin 侧</b>（UI:admin.product）；mp 只读查询接口在 GZ-JP-103 补
 * （{@code /app/gz/jp/product/*}），届时复用本模块的实体 / 枚举 / mapper，另建 mp VO。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-102)
 */
public interface IGzJpProductService {

    /**
     * 商品分页列表（UI:admin.product，顶部按场筛选）。
     *
     * <p>回填所属场的编号 / 名称 / <b>生效状态</b>，并派生 {@code visibleToCustomer}
     * （商品 on_shelf 且场 open 才是客人真看得到）。</p>
     *
     * @param query     筛选条件（eventId / productNo / name / status）
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    TableDataInfo<GzJpProductAdminVO> selectAdminPage(GzJpProductQueryBo query, PageQuery pageQuery);

    /**
     * 商品详情（编辑页回填）。
     *
     * @param id 商品主键
     * @return VO；不存在返回 null
     */
    GzJpProductAdminVO selectAdminById(Long id);

    /**
     * 新建商品（FLOW:F-JP-01.step2）—— status 固定 {@code off_shelf}，需显式上架后客人才可能看到。
     *
     * <p>校验所属场存在（不要求场已开场：step2 明确「未开场时仍不可见」，建场→上架→开场是正常顺序）。</p>
     *
     * @param bo 增改对象
     * @return 新建商品主键
     */
    Long insertByBo(GzJpProductBo bo);

    /**
     * 编辑商品 —— productNo / status 不可改（status 只能走 {@link #changeStatus}）。
     *
     * @param bo 增改对象（id 必传）
     * @return 是否成功
     */
    boolean updateByBo(GzJpProductBo bo);

    /**
     * 批量上下架（UI:admin.product「支持多选批量上下架」）。
     *
     * @param bo ids + 目标状态（on_shelf / off_shelf）
     * @return 实际改动行数
     */
    int changeStatus(GzJpProductStatusBo bo);

    /**
     * 逻辑删（软删）。已上架的商品不允许删除，需先下架 —— 与场「进行中不可删」同一保护口径。
     *
     * @param ids 商品主键集合
     * @return 是否成功
     */
    boolean deleteByIds(List<Long> ids);
}
