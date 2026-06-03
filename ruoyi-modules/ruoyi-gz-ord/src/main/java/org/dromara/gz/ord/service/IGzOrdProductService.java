package org.dromara.gz.ord.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.ord.domain.bo.GzOrdProductBo;
import org.dromara.gz.ord.domain.bo.GzOrdProductQueryBo;
import org.dromara.gz.ord.domain.dto.applet.OrdProductListReq;
import org.dromara.gz.ord.domain.dto.applet.ValidatePurchaseReq;
import org.dromara.gz.ord.domain.vo.GzOrdProductAdminVO;
import org.dromara.gz.ord.domain.vo.applet.OrdProductDetailVO;
import org.dromara.gz.ord.domain.vo.applet.OrdProductMpListVO;
import org.dromara.gz.ord.domain.vo.applet.ValidatePurchaseVO;

import java.util.List;

/**
 * 预购商品服务（GZ-ORD-101 admin CRUD + 截止下架）。
 *
 * <p>字段口径权威：doc/11 §6.1 / §6.2。商品 + SKU 同事务（决策 D1）；状态流转走 {@link #changeStatus}
 * （on_shelf ↔ off_shelf，auto_off 仅 cron）；软删 del_flag=2（被订单引用拒删，决策 D4）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
public interface IGzOrdProductService {

    /**
     * admin 分页查询（status / ipTag / name 筛选，AC 2 /list）。列表不投影 description_html / skuList。
     */
    TableDataInfo<GzOrdProductAdminVO> selectAdminPage(GzOrdProductQueryBo query, PageQuery pageQuery);

    /**
     * admin 详情（含 SKU 列表 + description_html，AC 2 /{id}）。不存在返回 null。
     */
    GzOrdProductAdminVO selectAdminById(Long id);

    /**
     * 新增商品 + 子 SKU（同事务，AC 2 POST）。product_no / sku_no 系统生成；status 固定 off_shelf；
     * description_html 落库前白名单清洗；到货日二选一校验（F6.1）。
     *
     * @return 新建商品主键
     */
    Long insertByBo(GzOrdProductBo bo);

    /**
     * 更新商品 + SKU diff（同事务，AC 2 PUT）。SKU 按 id diff：新增（id 空）/ 更新（id 存在）/ 删除
     * （提交列表缺失的既有 SKU —— 被订单引用的改 enabled=0 软停用，未引用的物理删，决策 D4 / R3）。
     * status / product_no / salesCount 不在编辑路径改。
     */
    boolean updateByBo(GzOrdProductBo bo);

    /**
     * 手动上下架（AC 2 changeStatus）。仅 on_shelf ↔ off_shelf；targetStatus=auto_off 拒绝
     * （决策 D5，auto_off 仅 cron 写，抛 INVALID_STATUS）。
     */
    boolean changeStatus(Long id, String targetStatus);

    /**
     * 软删（del_flag=2，AC 2 DELETE）。被订单引用的商品拒删（决策 D4，抛 PRODUCT_REFERENCED）。
     * 本 ticket 订单表（gz_ord_order）尚未建（ORD-104），引用检查留 hook（当前恒未引用）。
     */
    boolean deleteByIds(List<Long> ids);

    /**
     * 截止下架（AC 5，截止 SnailJob 委托的可单测 service 方法）。
     * {@code on_shelf AND deadline_time < now()} → {@code auto_off}（决策 D5 唯一写 auto_off 路径）。
     *
     * @return 实际自动下架商品数
     */
    int autoOffExpiredProducts();

    // ============================================================
    //  GZ-ORD-102 — mp 端 C 端浏览
    // ============================================================

    /**
     * mp 预购列表分页（GZ-ORD-102 AC1/AC2）。强约束 {@code status='on_shelf' AND deadline_time>NOW()
     * AND del_flag='0'}（兜底截止 cron 万一漏跑，auto_off/off_shelf 不返回）。排序按
     * {@link org.dromara.gz.ord.enums.OrdProductSortEnum}；ipTags 最多取前 10 个；主图解析为签名 URL，
     * 起始价取 enabled SKU MIN price_cent；外层返回 serverNow 供前端倒计时校准（决策 D4）。
     *
     * @param req 分页 + 排序 + ipTags 入参
     * @return rows + total + serverNow
     */
    OrdProductMpListVO listForMp(OrdProductListReq req);

    /**
     * mp 在售商品 IP 标签去重列表（GZ-ORD-102 AC3）。同 AC1 强约束（on_shelf + 未截止），
     * {@code DISTINCT ip_tag WHERE ip_tag IS NOT NULL}，供前端筛选 chip 渲染（不前端硬编码）。
     *
     * @return 在售 IP 标签列表（去重，无序保证 — 前端展示用）
     */
    List<String> listOnSaleIpTags();

    // ============================================================
    //  GZ-ORD-103 — mp 端 商品详情 + 下单前校验
    // ============================================================

    /**
     * mp 商品详情（GZ-ORD-103 AC1）。
     *
     * <p>不做在售强约束（详情页可被下架商品的旧链接打开 —— status 原样返回，由下单校验拦截，决策 D1）。
     * 主图 / 图集 file_id 解析为签名 URL（NULL / 解析失败 → 占位图，R4）；description_html 原文返回
     * （已白名单清洗）；SKU 列表 sort_no 升序（含停用 SKU 前端置灰）；外层附 serverNow 倒计时校准（决策 D2）。</p>
     *
     * @param id 商品主键
     * @return 详情 VO；商品不存在（含软删）→ {@code null}（controller 转 404 文案）
     */
    OrdProductDetailVO getDetailForMp(Long id);

    /**
     * mp 下单前校验（GZ-ORD-103 AC3，doc/10 §7.E1 / E2 / E3）。
     *
     * <p>校验链（任一不过即返对应 errCode，不抛异常）：</p>
     * <ol>
     *   <li>商品存在 + {@code status='on_shelf'} + {@code deadline_time > NOW()} → 否则 PRODUCT_OFF
     *       （下架 / 截止已过统一此码，强约束 #3）</li>
     *   <li>SKU 存在 + 属于该商品 + {@code enabled=1} + ({@code stock_remain IS NULL} 无限
     *       OR {@code stock_remain >= quantity}) → 否则 SKU_OUT_OF_STOCK</li>
     *   <li>商品 / SKU 不存在 → PRODUCT_NOT_FOUND</li>
     * </ol>
     *
     * <p>本校验仅为即时 UX 反馈；最终扣减以 ORD-104 提交时乐观锁为准（双重防线，决策 D1 / R2）。</p>
     *
     * @param req productId + skuId + quantity
     * @return 校验结果（ok + errCode）
     */
    ValidatePurchaseVO validatePurchase(ValidatePurchaseReq req);
}
