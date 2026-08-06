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
 * <p>admin 侧：建场 / 编辑 / 开场 / 关场 / 删除；mp 侧：只看得到「生效状态 = open」的场。</p>
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
     * mp 可下单场分页列表 —— 只返回<b>生效状态 = open</b> 的场（AC3：场未 open 时 mp 侧查不到；
     * 关场后 / end_time 已过的场不再出现）。
     *
     * <p>返回 {@code TableDataInfo}（rows + total），与资讯 mp 列表一致；
     * 契约由 doc/jp/verify.sh L1「场列表结构完整且不下发 draft」守。</p>
     *
     * @param pageQuery 分页参数
     * @return 分页结果（sort_no 升序 → start_time 升序）；无可下单场时 rows 为空数组
     */
    TableDataInfo<GzJpEventMpVO> selectMpPage(PageQuery pageQuery);

    /**
     * mp 场详情 —— 同样只对生效状态 open 的场开放。
     *
     * @param id 场主键
     * @return VO；场不存在 / 未开场 / 已结束 一律返回 null
     */
    GzJpEventMpVO selectMpDetail(Long id);

    /**
     * 场此刻是否可下单（下游 GZ-JP-102 商品上架校验 / GZ-JP-103 提交订单前置校验用，FLOW:F-JP-02.step3）。
     *
     * @param id 场主键
     * @return true = 生效状态为 open
     */
    boolean isBookable(Long id);
}
