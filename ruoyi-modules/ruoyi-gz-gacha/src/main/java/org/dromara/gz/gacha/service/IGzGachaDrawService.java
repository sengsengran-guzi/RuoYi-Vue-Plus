package org.dromara.gz.gacha.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.gacha.domain.vo.GachaStartDrawVo;
import org.dromara.gz.gacha.domain.vo.GzGachaDrawHistoryVo;
import org.dromara.gz.gacha.domain.vo.GzGachaDrawMachineFilterVo;
import org.dromara.gz.gacha.domain.vo.GzGachaDrawStatusVo;

import java.util.List;

/**
 * 扭蛋开盒服务（GZ-GACHA-104 ⭐ V1.1 最硬 ticket）。
 *
 * <p>业务流权威：doc/10 §8 扭蛋机开盒全流程（N4 付款前缺货拦截 / N6 开盒事务 4 表原子 / E5 失败重抽不退款）。
 * 业务模型钉死：付款必出 1 件实物，扭蛋域无系统自动退款。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
public interface IGzGachaDrawService {

    /**
     * mp 投币开盒（AC2 + AC2.5，doc/10 §8.N4-N5）。
     *
     * <p>① 付款前缺货拦截：机器 {@code status='on_shelf'} 且至少 1 个 prize {@code enabled=1 AND stock_remain>0}，
     * 否则抛 {@code MACHINE_EMPTY}（不收款）→ ② 生成 out_trade_no（GACHA- 前缀）→ ③ 调
     * {@code IGzPayTransactionService.createBusinessOrder} 建 pending 支付单 + 拿 mp 5 参 → ④ 记开盒意图
     * （machineId）入 Redis（供异步开盒恢复上下文）→ ⑤ 返回 out_trade_no + 5 参。<b>本步不写
     * gz_gacha_draw</b>（draw 仅在开盒事务成功时落，doc/11 F7.3）。</p>
     *
     * @param machineId 扭蛋机 id
     * @param userId    登录用户 id（sa-token）
     * @return out_trade_no + 微信 5 参签名
     */
    GachaStartDrawVo startDraw(Long machineId, Long userId);

    /**
     * 核心开盒事务（AC4，doc/10 §8.N6，4 表原子，必出 1 件，失败重抽不退款）。
     *
     * <p>{@code @Async("gachaExecutor")} 由支付回调触发（回调线程不阻塞，&lt; 3s）。事务严格顺序：
     * ① pay_transaction_id 幂等 → ② SELECT FOR UPDATE 锁在售有货候选 → ③ 有界重抽循环（归一化 + 随机出 1 件 +
     * 乐观锁扣减；affected=0 → 移出候选重抽，绝不退款）→ ④ INSERT draw → ⑤ UPSERT collection +1 →
     * ⑥ INSERT order(pending_ship) → COMMIT。事务外异步检测整机售罄 → auto_off（AC7）。</p>
     *
     * <p><b>无退款分支</b>：缺货付款前已拦（AC2.5），库存竞争失败走重抽改派；DB 系统级故障 → ROLLBACK 由
     * §6.E2 支付层重试该开盒事务（非退款）。</p>
     *
     * @param payTransactionId 支付订单 out_trade_no（= gz_pay_transaction.out_trade_no）
     */
    void executeDrawTransaction(String payTransactionId);

    /**
     * 开盒事务核心（AC4 同步事务体，{@code @Transactional} 不带 {@code @Async}）。
     *
     * <p>{@link #executeDrawTransaction} 仅做 {@code @Async} 派发 → 经 Spring 代理调本方法（事务边界在此真生效）。
     * <b>并发压测（AC9）直调本方法</b>：N 线程同步并发跑真实 SELECT FOR UPDATE + 乐观锁扣减，验证防超卖。
     * 幂等：pay_transaction_id 已落 draw → 直接 return。</p>
     *
     * @param payTransactionId 支付订单 out_trade_no
     */
    void runDrawTransaction(String payTransactionId);

    /**
     * mp 揭晓状态轮询（GZ-GACHA-105 AC1，doc/10 A.6 子集 / doc/12 §MP-GACHA-DRAW，<b>仅查不改库</b>）。
     *
     * <p>驱动三段分镜揭晓动画：mp 支付成功后用 start 返回的 out_trade_no（或 drawNo）轮询本端点。</p>
     *
     * <p><b>状态推断</b>（draw 仅在开盒成功时落，doc/11 F7.3）：查到 draw ⇒ {@code drawn}（附
     * {@code prize_snapshot_json} 解出的揭晓数据 + 图片签名 URL）；查不到 ⇒ {@code drawing}（付款后异步开盒中）。
     * 付款必出 1 件，<b>无 refunded 揭晓态</b>（扭蛋域无系统退款）。</p>
     *
     * <p><b>归属校验</b>：draw 已落但 {@code user_id != 当前用户} → 抛 {@code DRAW_FORBIDDEN}（非本人不可查他人开盒结果）。</p>
     *
     * @param payTransactionId 支付订单 out_trade_no（与 drawNo 二选一，优先 payTransactionId）
     * @param drawNo           开盒业务码 DRW-yyyyMMdd-6位（payTransactionId 为空时用）
     * @param userId           当前登录用户 id（归属校验）
     * @return 揭晓状态 VO（drawing：仅 status；drawn：status + drawNo + machineId + prize）
     */
    GzGachaDrawStatusVo getStatusForMp(String payTransactionId, String drawNo, Long userId);

    /**
     * mp「我开过的」开盒历史分页（GZ-GACHA-106 AC1/AC2，doc/12 §MP-GACHA-HISTORY）。
     *
     * <p>查当前用户 {@code gz_gacha_draw}（每行 = 一次成功开盒）按 {@code drawn_time} DESC 分页，走索引
     * {@code (tenant_id, user_id, drawn_time DESC)}。{@code machineId} 非空 → 追加 machine 过滤。
     * 每行 snapshot（machine / prize JSON）在 service 解为扁平 VO + 图片签名 URL（mp 端不解 JSON，决策 D6）；
     * 当前页 draw 批量取衍生订单 {@code business_status}（避免 N+1）填入 VO（AC2）。</p>
     *
     * <p><b>历史天然全部成功</b>（强约束 #1）：该表每行即一次成功开盒，无 draw_status 过滤、无退款记录。</p>
     *
     * @param userId    当前登录用户 id（sa-token，仅本人）
     * @param machineId 机器筛选（null = 不过滤「全部」）
     * @param pageQuery pageNum / pageSize
     * @return 历史列表分页（TableDataInfo：rows + total）
     */
    TableDataInfo<GzGachaDrawHistoryVo> listMyHistory(Long userId, Long machineId, PageQuery pageQuery);

    /**
     * mp 开盒历史「机器筛选 chip」列表（GZ-GACHA-106 AC3，本人历史 distinct machine）。
     *
     * <p>来源 = 本人 draw 的 distinct machine_id（不全量拉 {@code gz_gacha_machine}，决策 D3）；machineName
     * 解自该机器最近一条 draw 的 {@code machine_snapshot_json}。按最近开盒时间 DESC 排（最近开过的靠前）。</p>
     *
     * @param userId 当前登录用户 id（sa-token，仅本人）
     * @return [{machineId, machineName}]（本人开过的机器，按最近开盒 DESC；无历史 → 空 list）
     */
    List<GzGachaDrawMachineFilterVo> listMyHistoryMachines(Long userId);
}
