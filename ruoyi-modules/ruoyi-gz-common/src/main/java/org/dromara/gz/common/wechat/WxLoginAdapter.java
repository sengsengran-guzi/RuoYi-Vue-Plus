package org.dromara.gz.common.wechat;

/**
 * 微信小程序登录适配层接口。
 *
 * <p>本接口隔离两条通道：</p>
 * <ul>
 *   <li><b>Mock 通道</b>：{@link org.dromara.gz.common.wechat.impl.WxMockLoginAdapter} —
 *       {@code wx.miniapp.appid=wxMOCK} 时加载，跳过真实 jscode2session 调用，按 code 构造伪结果。</li>
 *   <li><b>Real 通道</b>：{@link org.dromara.gz.common.wechat.impl.WxRealLoginAdapter} —
 *       {@code wx.miniapp.appid != wxMOCK} 时加载，调微信开放接口 jscode2session。</li>
 * </ul>
 *
 * <p>Spring 通过 {@code @ConditionalOnProperty} 在启动期决定加载哪个 Bean，业务层 {@code IWxLoginService}
 * 只持有此接口，不感知具体通道（D2 决策：配置驱动，profile 切换零代码变更）。</p>
 *
 * <p>关联文档：doc/10 §1.N4 / GZ-SYS-002 任务卡关键技术决策 D2 / CLAUDE.md §6 #10 强约束 ADR 阈值</p>
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
