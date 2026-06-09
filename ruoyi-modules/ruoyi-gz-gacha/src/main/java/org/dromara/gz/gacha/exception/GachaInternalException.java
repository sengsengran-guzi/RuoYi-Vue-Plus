package org.dromara.gz.gacha.exception;

import java.io.Serial;

/**
 * 开盒事务系统级内部异常（GZ-GACHA-104 AC4.3 / doc/10 §8.E6）。
 *
 * <p><b>语义</b>：候选集被取空仍未扣减成功（理论不可达 —— 付款前已拦缺货 + FOR UPDATE 锁内库存不被外部抢，
 * 仅同一事务批次内竞争），或 DB 系统级故障。抛出后整事务 {@code @Transactional(rollbackFor=Exception.class)}
 * ROLLBACK → 由 doc/10 §6.E2 支付层重试该开盒事务（<b>非退款</b>，扭蛋域无系统退款）。</p>
 *
 * <p><b>不是</b>「抽空 → 退款」信号：并发扣减失败（乐观锁 affected=0）在事务内自循环重抽改派，<b>不</b>抛本异常。
 * 本异常仅在重抽循环耗尽候选（极端不可达）或底层 mapper 异常时出现。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
public class GachaInternalException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public GachaInternalException(String message) {
        super(message);
    }

    public GachaInternalException(String message, Throwable cause) {
        super(message, cause);
    }
}
