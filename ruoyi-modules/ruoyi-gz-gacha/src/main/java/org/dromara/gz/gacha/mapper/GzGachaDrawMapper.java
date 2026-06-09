package org.dromara.gz.gacha.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.gacha.domain.entity.GzGachaDraw;

import java.util.List;
import java.util.Map;

/**
 * gz_gacha_draw 数据层（GZ-GACHA-104）。
 *
 * <p>多租户 / 软删 / 乐观锁均由 ruoyi 拦截器自动处理。开盒事务幂等基于 {@code pay_transaction_id} 唯一索引
 * （doc/11 §7.3），本 mapper 提供事务首步幂等探测（已存在 → 直接 return，不重复扣库存 / 不重复建单）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
public interface GzGachaDrawMapper extends BaseMapperPlus<GzGachaDraw, GzGachaDraw> {

    /**
     * 按 pay_transaction_id 探测开盒记录是否已存在（开盒事务步骤 1 幂等，doc/10 §8.N6 / 强约束 #3）。
     *
     * <p>多租户由拦截器对注解 SQL 自动 append tenant 条件；软删行不计（del_flag='0'）。</p>
     *
     * @param payTransactionId 支付订单 out_trade_no
     * @return 已存在的开盒记录 id（null = 未开盒，可进事务）
     */
    @Select("SELECT id FROM gz_gacha_draw "
        + "WHERE pay_transaction_id = #{payTransactionId} AND del_flag = '0' LIMIT 1")
    Long selectIdByPayTransactionId(@Param("payTransactionId") String payTransactionId);

    /**
     * 按 pay_transaction_id 查开盒记录整行（GZ-GACHA-105 mp 揭晓轮询 status，仅查不改）。
     *
     * <p>归属校验在 service 层做（比对 {@code user_id}）；本查询不带 user 条件，便于 service 区分
     * 「未开盒(drawing)」与「非本人(403)」两态。多租户由拦截器对注解 SQL 自动 append；软删行不计。</p>
     *
     * @param payTransactionId 支付订单 out_trade_no（= start 返回的 outTradeNo）
     * @return 开盒记录（null = 尚未开盒，对应 drawing 态）
     */
    @Select("SELECT * FROM gz_gacha_draw "
        + "WHERE pay_transaction_id = #{payTransactionId} AND del_flag = '0' LIMIT 1")
    GzGachaDraw selectByPayTransactionId(@Param("payTransactionId") String payTransactionId);

    /**
     * 按 draw_no 查开盒记录整行（GZ-GACHA-105 mp 揭晓轮询 status 的 drawNo 入参分支，仅查不改）。
     *
     * @param drawNo 开盒业务码 DRW-yyyyMMdd-6位
     * @return 开盒记录（null = 不存在）
     */
    @Select("SELECT * FROM gz_gacha_draw "
        + "WHERE draw_no = #{drawNo} AND del_flag = '0' LIMIT 1")
    GzGachaDraw selectByDrawNo(@Param("drawNo") String drawNo);

    /**
     * 本人开盒历史「机器筛选 chip」列表（GZ-GACHA-106 AC3，distinct machine）。
     *
     * <p>来源 = 本人 draw 的 <b>distinct machine_id</b>，machineName 取该机器<b>最近一条</b> draw 的
     * {@code machine_snapshot_json}（{@code MAX(drawn_time)} 对应行，决策 D3 — 不全量拉 gz_gacha_machine）。
     * 用 {@code GROUP BY machine_id} + 关联子查询取最近 snapshot；按 {@code MAX(drawn_time) DESC} 排序
     * （最近开过的机器靠前）。多租户由拦截器对注解 SQL 自动 append tenant 条件；{@code @TableLogic} 不作用于
     * 原生注解 SQL，故显式 {@code del_flag='0'} 过滤软删。</p>
     *
     * @param userId 当前登录用户 id（sa-token，仅本人）
     * @return 每行 {@code {machineId(BIGINT), machineSnapshotJson(VARCHAR)}}（按最近开盒时间 DESC）
     */
    @Select("SELECT d.machine_id AS machineId, "
        + "(SELECT d2.machine_snapshot_json FROM gz_gacha_draw d2 "
        + " WHERE d2.machine_id = d.machine_id AND d2.user_id = #{userId} AND d2.del_flag = '0' "
        + " ORDER BY d2.drawn_time DESC, d2.id DESC LIMIT 1) AS machineSnapshotJson "
        + "FROM gz_gacha_draw d "
        + "WHERE d.user_id = #{userId} AND d.del_flag = '0' "
        + "GROUP BY d.machine_id "
        + "ORDER BY MAX(d.drawn_time) DESC")
    List<Map<String, Object>> selectMyHistoryMachines(@Param("userId") Long userId);
}
