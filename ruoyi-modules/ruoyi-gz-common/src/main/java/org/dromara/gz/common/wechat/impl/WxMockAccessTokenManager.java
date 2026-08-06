package org.dromara.gz.common.wechat.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.wechat.WxAccessTokenManager;
import org.dromara.gz.common.wechat.WxAdapterDispatcher;
import org.springframework.stereotype.Component;

/**
 * 微信小程序 access_token mock 实现。
 *
 * <p><b>装配</b>（ADR-0019 §1）：无条件注册；当前请求 clientid 对应的小程序为 mock 时由
 * {@link WxAdapterDispatcher} 运行时选中。返固定占位 token，不触网 —— dev / 单测下其它微信能力
 * （mock 发货客户端等）注入点可解析、不依赖真实证书。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
public class WxMockAccessTokenManager implements WxAccessTokenManager {

    private static final String MOCK_TOKEN = "mock_access_token";

    @Override
    public String getToken(boolean forceRefresh) {
        log.debug("[wx-token-mock] 返回 mock access_token（forceRefresh={}）", forceRefresh);
        return MOCK_TOKEN;
    }
}
