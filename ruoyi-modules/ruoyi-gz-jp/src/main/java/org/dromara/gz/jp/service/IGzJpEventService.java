package org.dromara.gz.jp.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.jp.domain.bo.GzJpEventBo;
import org.dromara.gz.jp.domain.bo.GzJpEventQueryBo;
import org.dromara.gz.jp.domain.vo.GzJpEventAdminVO;
import org.dromara.gz.jp.domain.vo.GzJpEventMpVO;
import org.dromara.gz.jp.domain.vo.GzJpEventOptionVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 拼团场服务（GZ-JP-101，FLOW:F-JP-01）。
 *
 * <p>admin 侧：建场 / 编辑 / 开场 / 关场 / 删除。</p>
 *
 * <p>mp 侧<b>两条粗细不同的闸</b>，不要混用：</p>
 * <ul>
 *   <li>{@link #isVisible}（宽）—— 能不能<b>看</b>：open / closed 都能看，draft 看不到。只读接口用它</li>
 *   <li>{@link #isBookable}（严）—— 能不能<b>下单</b>：仅生效状态 open。加购 / 下单 / 支付用它</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-101)
 */
public interface IGzJpEventService {

    // ============================================================
    //  admin（UI:admin.event）
    // ============================================================

    /**
     * 场分页列表（全状态 + 条件筛选）。
     *
     * @param query     筛选条件（status 按<b>生效状态</b>过滤，见 {@link GzJpEventQueryBo#getStatus()}）
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    TableDataInfo<GzJpEventAdminVO> selectAdminPage(GzJpEventQueryBo query, PageQuery pageQuery);

    /**
     * 场详情（编辑页回填）。
     *
     * @param id 场主键
     * @return VO；不存在返回 null
     */
    GzJpEventAdminVO selectAdminById(Long id);

    /**
     * 新建场（FLOW:F-JP-01.step1）—— status 固定 draft，客人不可见。
     *
     * @param bo 增改对象
     * @return 新建场主键
     */
    Long insertByBo(GzJpEventBo bo);

    /**
     * 编辑场 —— eventNo / status 不可改（status 只能走 {@link #open} / {@link #close}）。
     *
     * @param bo 增改对象（id 必传）
     * @return 是否成功
     */
    boolean updateByBo(GzJpEventBo bo);

    /**
     * 逻辑删（软删）。进行中的场不允许删除，需先关场。
     *
     * @param ids 场主键集合
     * @return 是否成功
     */
    boolean deleteByIds(List<Long> ids);

    /**
     * 开场（FLOW:F-JP-01.step3）—— status → open，小程序首页可见、场内 on_shelf 商品可下单。
     *
     * <p>前置：end_time 必须晚于当前时间（窗口已过的场无从开起）；已 open 的场不可重复开。
     * 允许把误关的场（stored=closed 但窗口未过）重新开起来 —— 店员误点关场的唯一补救路径。</p>
     *
     * @param id 场主键
     * @return 是否成功
     */
    boolean open(Long id);

    /**
     * 关场（FLOW:F-JP-01.step4 店员手动分支）—— status → closed，不可再下单。
     *
     * @param id 场主键
     * @return 是否成功
     */
    boolean close(Long id);

    // ============================================================
    //  跨 ticket 复用（GZ-JP-102 商品管理 / 后续订单与履约）
    // ============================================================

    /**
     * 全部场的轻量选项（下拉用）—— 按 sort_no 升序、id 降序（新场靠前）。
     *
     * <p>状态给的是<b>生效状态</b>（读时惰性判定），调用方无需自己比 end_time。</p>
     *
     * @return 选项列表（无场时空列表）
     */
    List<GzJpEventOptionVO> selectOptions();

    /**
     * 按 id 批量取场轻量信息 —— 给商品列表回填「所属场名称 / 场当前状态」。
     *
     * <p>★ 批量而非逐条 {@code isBookable(id)}：一页商品可能跨多个场，逐条会 N+1。
     * 状态判定仍收口在 {@code GzJpEventStatus.effective}，与 {@link #isBookable} 同一份逻辑。</p>
     *
     * @param ids 场主键集合（null / 空 → 空 Map）
     * @return id → 轻量信息；已软删 / 不存在的 id 不出现在结果里
     */
    Map<Long, GzJpEventOptionVO> selectOptionMap(Collection<Long> ids);

    // ============================================================
    //  mp（FLOW:F-JP-02.step1）
    // ============================================================

    /**
     * mp 可浏览场分页列表 —— 返回<b>开过的场（open / closed）</b>，draft 一律不下发。
     *
     * <p>UI:mp.home 要求首页把场分「进行中 / 即将开始 / 已结束」三组展示，所以已结束的场必须下发；
     * 「能不能下单」由每行的 {@link GzJpEventMpVO#getStatus()}（生效状态）表达，
     * {@code open} = 可下单，{@code closed} = 已结束只可浏览。
     * 「即将开始」= {@code status=open 且 startTime &gt; now}，前端自行派生，不是第四种状态。</p>
     *
     * <p>返回 {@code TableDataInfo}（rows + total），与资讯 mp 列表一致；
     * 契约由 doc/jp/verify.sh L1「场列表结构完整且不下发 draft」守。</p>
     *
     * @param pageQuery 分页参数
     * @return 分页结果（sort_no 升序 → end_time 倒序：未结束的天然排在已结束之前，组内新场在前）；
     *         无可浏览场时 rows 为空数组
     */
    TableDataInfo<GzJpEventMpVO> selectMpPage(PageQuery pageQuery);

    /**
     * mp 场详情 —— 对开过的场（open / closed）开放，draft 不下发。
     *
     * <p>已结束的场返回的 VO 里 {@code status=closed}，UI:mp.event_detail 据此显示「本场已结束」
     * 并置灰加购入口；不是返回 null 让前端拿不到场名封面。</p>
     *
     * @param id 场主键
     * @return VO；场不存在 / 未开场（draft）返回 null
     */
    GzJpEventMpVO selectMpDetail(Long id);

    /**
     * 场此刻<b>是否可下单</b>（加购 / 提交订单 / 支付前置校验，FLOW:F-JP-02.step3）。
     *
     * <p>★ 严格判定，写路径只认这个：关场 / 到 end_time 后立刻 false。
     * 别拿它当可见性用 —— 那是 {@link #isVisible}。</p>
     *
     * @param id 场主键
     * @return true = 生效状态为 open
     */
    boolean isBookable(Long id);

    /**
     * 场此刻<b>是否可浏览</b>（mp 只读路径的可见性闸，比 {@link #isBookable} 宽）。
     *
     * <p>open / closed 都算可浏览，只有 draft（含从未开过、窗口已过的 draft）不可浏览。
     * 商品只读接口用它卡门，用 {@link #isBookable} 派生「加购能不能点」。</p>
     *
     * @param id 场主键
     * @return true = 存库状态是 open 或 closed
     */
    boolean isVisible(Long id);
}
