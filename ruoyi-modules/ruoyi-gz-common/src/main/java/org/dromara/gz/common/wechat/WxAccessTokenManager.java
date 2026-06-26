package org.dromara.gz.common.wechat;

/**
 * 微信小程序全局 access_token 管理器。
 *
 * <p>微信侧 access_token <b>全局唯一</b>（同一时刻只有一个有效），且被本应用多项微信能力共享
 * （手机号 getuserphonenumber / 发货信息录入 upload_shipping_info / 后续客服 / 订阅消息）。集中在
 * 本管理器用 Redis 缓存复用（key {@code wx:miniapp:access_token}），避免各能力各自刷新互相把对方的
 * token 顶失效。</p>
 *
 * <p>real / mock 双实现（与 {@link WxLoginAdapter} / {@link WxPhoneAdapter} 同款 wx.miniapp.appid
 * 条件切换）：real 调 {@code cgi-bin/token} 真拉；mock 返固定占位 token（dev 无网络依赖）。</p>
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
