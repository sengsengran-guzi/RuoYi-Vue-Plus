package org.dromara.gz.common.wechat;

/**
 * 微信小程序登录适配层接口。
 *
 * <p>本接口隔离两条通道：</p>
 * <ul>
 *   <li><b>Mock 通道</b>：{@link org.dromara.gz.common.wechat.impl.WxMockLoginAdapter} —
 *       跳过真实 jscode2session 调用，按 code 构造伪结果。</li>
 *   <li><b>Real 通道</b>：{@link org.dromara.gz.common.wechat.impl.WxRealLoginAdapter} —
 *       用该小程序自己的 appid/secret 调微信开放接口 jscode2session。</li>
 * </ul>
 *
 * <p><b>选哪条通道是运行时决定的</b>（ADR-0019 §1）：两个实现都注册为 Bean，
 * {@link WxAdapterDispatcher}（{@code @Primary} 门面）按当前请求 header {@code clientid} 查出所属小程序，
 * 该小程序 {@code appid} 为空或 {@code wxMOCK} 即走 mock。业务层 {@code IWxLoginService} 只持有此接口
 * （注入到的就是门面），不感知具体通道；同一进程内 A 小程序 real、B 小程序 mock 互不干扰。</p>
 *
 * <p>关联文档：ADR-0019 §1 / doc/10 §1.N4 / GZ-SYS-002 任务卡关键技术决策 D2</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
public interface WxLoginAdapter {

    /**
     * 用 wx.login() 返回的临时 code 换 openid / unionid / session_key。
     *
     * @param code 小程序 wx.login() 返回的临时凭证（≤32 位，5 分钟有效）
     * @return jscode2session 解构结果
     * @throws org.dromara.common.core.exception.ServiceException
     *         <ul>
     *           <li>code 为空 / null → "微信登录 code 不能为空"</li>
     *           <li>微信侧 errcode != 0 → "微信登录失败: {errmsg}"</li>
     *         </ul>
     */
    WxJscode2SessionResult code2Session(String code);

    /**
     * 返回当前 adapter 通道名（便于日志区分 / 单测断言）。
     *
     * @return "mock" / "real"
     */
    String channel();
}
