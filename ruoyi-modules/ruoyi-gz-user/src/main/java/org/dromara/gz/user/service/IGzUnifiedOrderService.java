package org.dromara.gz.user.service;

import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.user.domain.vo.GzUnifiedOrderVo;
import org.dromara.gz.user.domain.vo.UnifiedOrderDetailVo;

/**
 * 统一订单聚合服务（GZ-USER-101）—— 跨 {@code gz_ord_order}（预购）+ {@code gz_gacha_order}（扭蛋）
 * 的 <b>逻辑视图</b>，service 层 UNION 实现（不建 MySQL VIEW、不建物化表，doc/11 §8.1 F8.1 / 决策 D1）。
 *
 * <p><b>聚合口径</b>（doc/11 §8.1 + D10 README §1）：
 * <ul>
 *   <li>{@code bizType=preorder} → 仅查 gz_ord_order（贴字面量 business_type='preorder'）</li>
 *   <li>{@code bizType=gacha}    → 仅查 gz_gacha_order（贴字面量 business_type='gacha'）</li>
 *   <li>{@code bizType=all}      → 并查两表，各自映射成统一 VO 后合并（决策 D2 单类筛走单表，AC4）</li>
 * </ul>
 * 合并后按 {@code created_at DESC} 排序、内存分页（pageSize 上限 50，AC10）。tenant_id / del_flag 过滤
 * 由 ruoyi 拦截器自动加（AC6）；仅返当前登录用户订单（AC7）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-101)
 */
public interface IGzUnifiedOrderService {

    /**
     * 当前用户的统一订单列表分页（AC1~AC10）。
     *
     * @param userId   当前登录 app_user id（由 controller 从 sa-token 取，不信任前端，AC7）
     * @param bizType  all / preorder / gacha（缺省 / 非法 → all）
     * @param pageNum  页码（从 1 起；缺省 1）
     * @param pageSize 每页条数（缺省 10，上限 50 超限截断，AC10）
     * @return 统一 VO 分页（rows = {@link GzUnifiedOrderVo}，时间倒序）
     */
    TableDataInfo<GzUnifiedOrderVo> listByUser(Long userId, String bizType, Integer pageNum, Integer pageSize);

    /**
     * 单订单统一详情（GZ-USER-102 AC1~AC5）—— = 统一列表 VO 同字段集 + 业务专属差异块。
     *
     * <p>按 {@code orderNo} 前缀（{@code PREORD-} / {@code GACHA-}）判 businessType → 查对应只读表
     * → <b>校验 user_id 归属</b>（非本人 / 不存在 → 返 null，controller 转 403/未找到，AC1）→ 解
     * {@code *_snapshot_json} 拼差异字段（扭蛋 5 字段 / 预购 7 字段，AC2）。snapshot 直读不实时 join
     * 主数据表（AC3 / 决策 D3）；图片字段返 image_id（前端换签名 URL，AC3）。</p>
     *
     * @param orderNo 订单业务码（PREORD-... / GACHA-...）
     * @param userId  当前登录 app_user id（controller 从 sa-token 取，不信任前端，AC1）
     * @return 统一详情 VO；非本人 / 不存在 / 非法 orderNo → null
     */
    UnifiedOrderDetailVo getDetail(String orderNo, Long userId);
}
