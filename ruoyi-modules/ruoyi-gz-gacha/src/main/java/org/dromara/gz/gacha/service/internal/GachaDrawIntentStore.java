package org.dromara.gz.gacha.service.internal;

import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 开盒意图存储（GZ-GACHA-104）—— 把「out_trade_no → machineId」映射存 Redis（TTL 30min）。
 *
 * <p><b>为什么独立 Bean</b>：① 把 {@link RedisUtils} 静态依赖封一层，service 注入本 Bean 后单测可 mock，
 * 不踩 {@code mockStatic(RedisUtils)} 因 RedissonClient 静态初始化失败的坑；② draw 仅在开盒成功时落
 * （doc/11 F7.3），建支付单时无业务订单可存 machineId，故用 Redis 意图临时承载，异步开盒据 out_trade_no 取回。</p>
 *
 * <p><b>租户前缀一致性（关键坑，实测踩过）</b>：ruoyi {@code TenantKeyPrefixHandler} 在有租户上下文时给
 * Redis key 自动加 {@code <tenantId>:} 前缀。{@code save} 在 mp 请求线程（有租户 1001）写 →
 * {@code 1001:gz:gacha:...}；但 {@code getMachineId} 在 {@code @Async} 开盒线程（<b>无租户上下文</b>）读 →
 * 无前缀 {@code gz:gacha:...} → 读不到 → 「开盒意图丢失」。<b>统一用 {@link TenantHelper#ignore} 包裹</b>
 * 三个操作 → key 恒无租户前缀，写读两端一致（扭蛋意图非租户隔离数据，全局 key 即可）。</p>
 *
 * <p>TTL 30min &gt; 支付超时 5min，覆盖回调延迟；意图丢失（Redis 重启 / 超长延迟）→ 开盒事务抛
 * {@code GachaInternalException} 走支付层重试（非退款）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
@Component
public class GachaDrawIntentStore {

    private static final String KEY_PREFIX = "gz:gacha:draw-intent:";
    private static final Duration TTL = Duration.ofMinutes(30);

    /**
     * 记开盒意图（out_trade_no → machineId）。{@link TenantHelper#ignore} 保证 key 无租户前缀（与异步读一致）。
     *
     * @param outTradeNo 支付订单 out_trade_no
     * @param machineId  机器 id
     */
    public void save(String outTradeNo, Long machineId) {
        TenantHelper.ignore(() -> RedisUtils.setCacheObject(KEY_PREFIX + outTradeNo, machineId, TTL));
    }

    /**
     * 取开盒意图 machineId（异步开盒恢复上下文）。{@link TenantHelper#ignore} 保证与写入端 key 一致（无租户前缀）。
     *
     * <p>容错回读：Redisson 编解码可能回 {@code Long}/{@code Integer}/{@code String}（取决于写入路径），
     * 统一 {@code String.valueOf} 后解析，避免硬 cast 抛 {@code ClassCastException}。</p>
     *
     * @param outTradeNo 支付订单 out_trade_no
     * @return machineId；意图丢失返回 null
     */
    public Long getMachineId(String outTradeNo) {
        Object v = TenantHelper.ignore(() -> RedisUtils.getCacheObject(KEY_PREFIX + outTradeNo));
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        return Long.parseLong(s);
    }

    /**
     * 清理开盒意图（开盒成功后）。
     *
     * @param outTradeNo 支付订单 out_trade_no
     */
    public void clear(String outTradeNo) {
        TenantHelper.ignore(() -> RedisUtils.deleteObject(KEY_PREFIX + outTradeNo));
    }
}
