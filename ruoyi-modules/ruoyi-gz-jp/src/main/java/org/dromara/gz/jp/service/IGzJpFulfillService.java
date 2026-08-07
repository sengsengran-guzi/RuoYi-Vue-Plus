package org.dromara.gz.jp.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.jp.domain.bo.GzJpFulfillAdvanceBo;
import org.dromara.gz.jp.domain.bo.GzJpFulfillQueryBo;
import org.dromara.gz.jp.domain.bo.GzJpFulfillShipBo;
import org.dromara.gz.jp.domain.vo.GzJpFulfillBatchResultVO;
import org.dromara.gz.jp.domain.vo.GzJpFulfillBoardItemVO;

/**
 * 履约推进服务（GZ-JP-106，FLOW:F-JP-03）。
 *
 * <p><b>状态挂在商品行上，不是订单上</b>（REQ-FULFILL-003）：一单 30 款各自进度不同。
 * 因此这里所有入参的 id 都是 {@code gz_jp_order_item.id}。</p>
 *
 * <p><b>三条贯穿全服务的铁律</b>：</p>
 * <ol>
 *   <li><b>只碰付过款的订单的行</b> —— {@code fulfill_status} 列默认值就是 {@code purchasing}，
 *       未支付订单的行看起来也在「购买中」。三个方法（含只读看板）全部内置这道闸，不提供关闭开关。</li>
 *   <li><b>合法性判定唯一真源是 {@code GzJpFulfillStateMachine}</b> —— 本类不自己写第二套 if。</li>
 *   <li><b>批量写必须先按 id 升序锁行</b> —— 两个店员勾了重叠的行是真实场景，
 *       乱序加锁会死锁（本项目购物车加购踩过）。</li>
 * </ol>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
public interface IGzJpFulfillService {

    /**
     * 批量推进履约状态（FLOW:F-JP-03.step2）。
     *
     * <p><b>★ 允许跳过中间态</b>（现货直接 购买中 → 日本仓库已发货）；
     * <b>拒绝倒退与跨越终态</b>；<b>拒绝目标 {@code delivered}</b>（那要填运单号，走 {@link #ship}）。</p>
     *
     * <p>部分成功语义：能推的推掉，推不动的逐行给原因。
     * 只有「一行都没推动且也没有幂等跳过」才抛 {@code 4108}。</p>
     *
     * @param bo         行 id 集合 + 目标状态
     * @param operatorId 操作人 {@code sys_user.id}（写入 update_by；可为 null）
     * @return 计数 + 被拒明细
     * @throws org.dromara.common.core.exception.ServiceException 目标非法 / 4108 / 4109
     */
    GzJpFulfillBatchResultVO advance(GzJpFulfillAdvanceBo bo, Long operatorId);

    /**
     * 批量发货（FLOW:F-JP-03.step3）—— 一批行置 {@code delivered} 并共用一个运单号。
     *
     * <p><b>★ carrierCode 与 trackingNo 必填</b>（4109）；<b>★ 必须同一客人</b>（4110）——
     * 一个运单号 = 一个包裹 = 一个收件人，<b>不另建包裹表</b>。</p>
     *
     * @param bo         行 id 集合 + 快递公司 + 运单号
     * @param operatorId 操作人 {@code sys_user.id}
     * @return 计数 + 被拒明细（含本次写入的快递 / 单号）
     * @throws org.dromara.common.core.exception.ServiceException 4108 / 4109 / 4110
     */
    GzJpFulfillBatchResultVO ship(GzJpFulfillShipBo bo, Long operatorId);

    /**
     * 履约看板分页（UI:admin.fulfill_board；GZ-JP-108 的数据源）。
     *
     * <p>结果<b>恒按客人聚簇</b>（user_id → order_id → item id），前端断组渲染即可。</p>
     *
     * @param query     筛选条件（全部可选）
     * @param pageQuery 分页
     * @return 看板行分页（{@code rows} 不是 {@code data}）
     */
    TableDataInfo<GzJpFulfillBoardItemVO> selectBoardPage(GzJpFulfillQueryBo query, PageQuery pageQuery);
}
