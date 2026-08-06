package org.dromara.gz.jp.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.jp.domain.bo.GzJpProductBo;
import org.dromara.gz.jp.domain.bo.GzJpProductQueryBo;
import org.dromara.gz.jp.domain.bo.GzJpProductStatusBo;
import org.dromara.gz.jp.domain.vo.GzJpProductAdminVO;
import org.dromara.gz.jp.domain.vo.GzJpProductMpVO;

import java.util.List;

/**
 * 拼团商品服务（GZ-JP-102 admin 写侧 + GZ-JP-103 mp 读侧，FLOW:F-JP-01.step2 / F-JP-02.step1）。
 *
 * <p>admin 侧（UI:admin.product）与 mp 侧（{@code /app/gz/jp/product/*}）共用同一份实体 / 枚举 / mapper，
 * 但<b>各自的 VO 不复用</b> —— mp 不下发 remark / version / sortNo 等运营字段，且图片给 URL 不给 file id。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-102 / GZ-JP-103)
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

    // ============================================================
    //  mp（GZ-JP-103，FLOW:F-JP-02.step1「进场看商品」）
    // ============================================================

    /**
     * 场内商品分页（UI:mp.event_detail 两列网格，上拉加载）。
     *
     * <p><b>★ 可见性两层，缺一即漏</b>：{@code 商品 status = on_shelf} <b>且</b>
     * {@code 所属场可浏览}（{@link IGzJpEventService#isVisible}：open / closed 都算，draft 不算）。
     * 只判商品 status 会把「未开场的场里的 on_shelf 商品」漏给客人
     * —— FLOW:F-JP-01.step2 明确「未开场时仍不可见」。</p>
     *
     * <p><b>★ 闸用「可浏览」不是「可下单」</b>：UI:mp.event_detail 要求「场已结束时商品仍可浏览，
     * 加购入口置灰」，所以已结束场照常出商品；每行的 {@link GzJpProductMpVO#getEventBookable()}
     * 告诉前端加购能不能点。用 {@code isBookable} 卡门会让已结束场的商品网格整个空掉。</p>
     *
     * <p>场不可浏览（draft / 不存在）时返回<b>空页</b>（rows=[] / total=0）而非报错：mp 进场前已先打
     * {@code GET /app/gz/jp/event/{id}} 拿到明确信号，此处只做兜底不抢话。</p>
     *
     * @param eventId   场主键（必传）
     * @param pageQuery 分页参数
     * @return 分页结果（按 sort_no 升序，同序号按上架先后）
     */
    TableDataInfo<GzJpProductMpVO> selectMpPage(Long eventId, PageQuery pageQuery);

    /**
     * 商品详情（UI:mp.product_detail，含 ★ noticeText 风险告知位）。
     *
     * <p>可见性判定与 {@link #selectMpPage} 完全同口径（两层），已结束场的商品同样下发，
     * 靠 {@link GzJpProductMpVO#getEventBookable()}{@code =false} 让前端置灰 CTA。</p>
     *
     * @param id 商品主键
     * @return VO；商品不存在 / 已下架 / 所属场不可浏览（draft）一律返回 null（由 controller 转 R.fail）
     */
    GzJpProductMpVO selectMpDetail(Long id);
}
