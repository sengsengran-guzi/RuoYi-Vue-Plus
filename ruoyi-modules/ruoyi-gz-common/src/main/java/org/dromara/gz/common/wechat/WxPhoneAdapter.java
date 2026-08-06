package org.dromara.gz.common.wechat;

/**
 * 微信小程序手机号获取适配层接口。
 *
 * <p>{@code <button open-type="getPhoneNumber">} 授权后回调返回一个动态 {@code code}（基础库 2.21.2+，
 * 非明文手机号），后端用本接口把 code 换成明文手机号。两条通道与 {@link WxLoginAdapter} 同款对偶：</p>
 * <ul>
 *   <li><b>Mock 通道</b>：{@link org.dromara.gz.common.wechat.impl.WxMockPhoneAdapter} —
 *       忽略 code 直接返固定测试号。</li>
 *   <li><b>Real 通道</b>：{@link org.dromara.gz.common.wechat.impl.WxRealPhoneAdapter} —
 *       用该小程序的 access_token 调微信 {@code getuserphonenumber} 换明文。</li>
 * </ul>
 *
 * <p><b>选哪条通道是运行时决定的</b>（ADR-0019 §1）：两个实现都注册为 Bean，由
 * {@link WxAdapterDispatcher}（{@code @Primary} 门面）按当前请求 clientid 对应小程序的 mode 选择。
 * 本能力<b>只可能被小程序调用</b>（控制器挂 {@code /app/**}），故走
 * {@link WxAppResolver#currentApp()} 严格口径 —— 未登记的 clientid 直接抛错。</p>
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
