package org.dromara.gz.common.wechat;

/**
 * 微信小程序手机号获取适配层接口。
 *
 * <p>{@code <button open-type="getPhoneNumber">} 授权后回调返回一个动态 {@code code}（基础库 2.21.2+，
 * 非明文手机号），后端用本接口把 code 换成明文手机号。两条通道与 {@link WxLoginAdapter} 同款对偶：</p>
 * <ul>
 *   <li><b>Mock 通道</b>：{@link org.dromara.gz.common.wechat.impl.WxMockPhoneAdapter} —
 *       {@code wx.miniapp.appid=wxMOCK} 时加载，忽略 code 直接返固定测试号。</li>
 *   <li><b>Real 通道</b>：{@link org.dromara.gz.common.wechat.impl.WxRealPhoneAdapter} —
 *       真实 AppID 时加载，用 access_token 调微信 {@code getuserphonenumber} 换明文。</li>
 * </ul>
 *
 * <p>关联文档：doc/10 §1.N8 + §3.N6 手机号强收集 / GZ-BEAN-004 任务卡</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
public interface WxPhoneAdapter {

    /**
     * 用 getPhoneNumber 回调返回的动态 code 换取用户明文手机号。
     *
     * @param code {@code e.detail.code}（动态令牌，5 分钟有效，一次性）
     * @return 11 位中国大陆手机号
     * @throws org.dromara.common.core.exception.ServiceException code 为空 / 微信侧失败 / 手机号为空时抛
     */
    String code2Phone(String code);

    /**
     * 返回当前 adapter 通道名（便于日志区分 / 单测断言）。
     *
     * @return "mock" / "real"
     */
    String channel();
}
