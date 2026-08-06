package org.dromara.gz.common.wechat;

/**
 * 微信小程序全局 access_token 管理器。
 *
 * <p>微信侧 access_token 是 <b>appid 维度</b>凭证（同一 appid 同一时刻只有一个有效），且被本应用多项
 * 微信能力共享（手机号 getuserphonenumber / 发货信息录入 upload_shipping_info / 后续客服 / 订阅消息）。
 * 集中在本管理器用 Redis 缓存复用，避免各能力各自刷新互相把对方的 token 顶失效。</p>
 *
 * <p>real / mock 双实现都注册为 Bean，由 {@link WxAdapterDispatcher}（{@code @Primary} 门面）
 * 运行时选择（ADR-0019 §1）：real 调 {@code cgi-bin/token} 真拉；mock 返固定占位 token（dev 无网络依赖）。</p>
 *
 * <p><b>本能力是共享能力</b>：除小程序侧外，还会被 {@code @Async} 发货上报、cron、以及后台 admin
 * 「发货信息手动补报」调用 —— 那些线程带的 clientid 不是小程序，故走
 * {@link WxAppResolver#currentAppOrDefault()} 宽松口径（认不出即落 {@code default-client-id}）。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
public interface WxAccessTokenManager {

    /**
     * 取 access_token。
     *
     * @param forceRefresh true = 跳过 Redis 缓存强制重拉（token 失效 errcode 40001/42001/40014 重试场景）
     * @return 有效 access_token
     */
    String getToken(boolean forceRefresh);
}
