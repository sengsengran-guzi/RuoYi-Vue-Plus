package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanFreePromoBo;
import org.dromara.gz.bean.domain.vo.GzBeanFreePromoStatusVO;
import org.dromara.gz.bean.domain.vo.GzBeanFreePromoVO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 拼豆前 N 名免费促销服务（GZ-BEAN-025，ADR-0015 §4 / doc/11 §3.11）。
 *
 * <p>三块能力：</p>
 * <ul>
 *   <li>admin CRUD（每门店一行配置）；</li>
 *   <li>mp 促销提示状态 {@link #getStatus(Long)}（remaining / periodLabel）；</li>
 *   <li>下单事务内原子发放 {@link #evaluateAndLockBucket} + {@link #releaseBucket}
 *       —— 由 {@code submitPaid} 接入，Redis 桶锁串行化 + 桶内已发计数比对名额，不超发。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-025)
 */
public interface IGzBeanFreePromoService {

    // ============================================================
    //  admin CRUD
    // ============================================================

    /** admin 分页列表（按门店 enrich 门店名） */
    TableDataInfo<GzBeanFreePromoVO> selectPageList(PageQuery pageQuery);

    /** 按 ID 详情 */
    GzBeanFreePromoVO selectVoById(Long id);

    /** 按门店查配置（每门店一行；不存在返 null） */
    GzBeanFreePromoVO selectByStoreId(Long storeId);

    /** 新增（每门店一行，store_id UNIQUE 由 DB + service 双兜底；days 周期校验 anchorDate） */
    boolean insertByBo(GzBeanFreePromoBo bo);

    /** 编辑（按 id 更新，storeId 不可改） */
    boolean updateByBo(GzBeanFreePromoBo bo);

    /** 软删（按 id 集合） */
    boolean deleteByIds(Collection<Long> ids);

    // ============================================================
    //  mp 促销提示
    // ============================================================

    /**
     * mp 促销提示状态（doc/11 §3.11）：返回 {@code enabled / freeCount / remaining / periodLabel}。
     * 促销关 / 不在窗口 → enabled=false、remaining=0、periodLabel=null（mp 显真实价格不显 banner）。
     *
     * @param storeId 门店 id
     * @return 状态 VO（storeId 为 null 或门店无配置 → enabled=false 占位 VO）
     */
    GzBeanFreePromoStatusVO getStatus(Long storeId);

    // ============================================================
    //  下单事务内原子发放（submitPaid 接入）
    // ============================================================

    /**
     * 下单事务内：判断本单是否命中前 N 名免费，命中且名额未满则<b>持有桶锁</b>返回 free=true。
     *
     * <p>语义（ADR-0015 §4 / doc/11 §3.11）：</p>
     * <ol>
     *   <li>促销未配置 / 关 / 不在 {@code [start_date, end_date]} 窗口 → free=false（不抢锁）。</li>
     *   <li>命中 → 取当前周期桶 → 抢 Redis 桶锁 {@code gz:bean:lock:free_promo:{store}:{bucket}}
     *       串行化（抢锁失败视为名额竞争激烈 → free=false 走正常计价，不阻塞下单）。</li>
     *   <li>桶内已发计数 {@code < free_count} → free=true（本单免费）；否则 free=false。</li>
     * </ol>
     *
     * <p><b>桶锁必须持有到 booking INSERT 完成后再释放</b>（调用方在 finally 调 {@link #releaseBucket}）——
     * 否则两并发单都在对方 INSERT 提交前读到同一计数 → 超发。本方法只抢锁 + 计数 + 决策，不做 INSERT
     * （INSERT 由 submitPaid 完成，确保锁跨越「计数→插入」临界区）。</p>
     *
     * @param storeId   门店
     * @param tenantId  租户（mp 用户态 JWT 无可靠 tenant，由 submitPaid 显式传）
     * @param orderTime 下单时刻（决定周期桶 + 窗口判断）
     * @return 发放决策（含是否免费 + 已持有的桶锁 key，供 releaseBucket 释放）
     */
    FreeGrantDecision evaluateAndLockBucket(Long storeId, String tenantId, LocalDateTime orderTime);

    /**
     * 释放 {@link #evaluateAndLockBucket} 持有的桶锁（INSERT 完成后调用，幂等 — 未持锁时 no-op）。
     */
    void releaseBucket(FreeGrantDecision decision);

    /**
     * 前 N 名免费发放决策（{@link #evaluateAndLockBucket} 返回值）。
     *
     * @param free        本单是否免费（命中促销 + 名额未满 + 桶锁抢到）
     * @param bucketLockKey 已持有的桶锁 Redis key（free=true 时非空，供 releaseBucket）；free=false 为 null
     */
    record FreeGrantDecision(boolean free, String bucketLockKey) {
        /** 未命中 / 未免费的常量决策（无持锁）。 */
        public static FreeGrantDecision notFree() {
            return new FreeGrantDecision(false, null);
        }
    }

    /**
     * 当前周期桶时间范围 [start, end)（内部计算，暴露供 status 接口 + 单测复用）。
     *
     * @param periodType  day / week / days
     * @param periodDays  days 周期天数
     * @param anchorDate  days 锚点
     * @param refDate     参考日期（下单日 / 当天）
     * @return [bucketStart 00:00, bucketEnd 00:00)
     */
    LocalDateTime[] computeBucketRange(String periodType, Integer periodDays, LocalDate anchorDate, LocalDate refDate);
}
