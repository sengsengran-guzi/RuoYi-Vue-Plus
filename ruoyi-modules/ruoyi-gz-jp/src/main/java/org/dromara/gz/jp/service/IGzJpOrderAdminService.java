package org.dromara.gz.jp.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.jp.domain.bo.GzJpOrderQueryBo;
import org.dromara.gz.jp.domain.vo.GzJpOrderAdminDetailVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderAdminVO;

/**
 * admin 订单管理服务（GZ-JP-109，UI:admin.order）—— <b>纯只读</b>。
 *
 * <p><b>接口里一个写方法都没有，这是本 ticket 的 AC 而不是偷懒</b>：
 * 订单的写路径只有三条，各有其归属，本页一条都不碰 ——
 * 下单（mp，GZ-JP-105）/ 支付回调（GZ-PAY SPI，GZ-JP-105）/ 履约推进与发货（履约看板，GZ-JP-106+108）。
 * 查单页多开一个写口子，就等于绕过履约看板的批量语义与状态机守卫。</p>
 *
 * <p><b>与 {@link IGzJpOrderService} 的分工</b>：那个是客人侧写路径（下单 / 回调 / 我的订单），
 * 本接口是店员侧读路径。分开是因为两边的 VO 口径不同 ——
 * admin 要下发客人身份与支付流水，mp 一个字节都不能给。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-109)
 */
public interface IGzJpOrderAdminService {

    /**
     * 订单列表分页（UI:admin.order 列表）。
     *
     * <p><b>★ 不过滤付款状态</b>：{@code created}（待支付）与 {@code cancelled}（已取消）
     * 的订单必须能查到。履约看板的 {@code isPaidLike} 闸是采购视角专属，本页是资金视角。</p>
     *
     * @param query     筛选条件（可为 null = 不筛选）
     * @param pageQuery 分页
     * @return 列表页（读 {@code rows} / {@code total}）
     */
    TableDataInfo<GzJpOrderAdminVO> selectAdminPage(GzJpOrderQueryBo query, PageQuery pageQuery);

    /**
     * 订单详情（UI:admin.order 详情抽屉）—— 订单头 + 逐行商品及履约状态 + 收货地址。
     *
     * @param orderId 订单 id
     * @return 详情
     * @throws org.dromara.common.core.exception.ServiceException 订单不存在 / 已删
     */
    GzJpOrderAdminDetailVO getAdminDetail(Long orderId);
}
