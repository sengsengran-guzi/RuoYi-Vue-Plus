package org.dromara.gz.common.wechat.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.wechat.WxPhoneAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 微信手机号 mock 通道实现。
 *
 * <p>启用条件：{@code wx.miniapp.appid=wxMOCK}（含默认值场景）。与 {@link WxMockLoginAdapter} 同款
 * {@code matchIfMissing=true} —— AppID 未配时默认走 mock，忽略 code 返固定测试号，让拼豆预约 / 个人
 * 资料手机号流程在 dev + 开发者工具下可走通（同 mp 端 wxMOCK 短路约定）。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wx.miniapp", name = "appid", havingValue = "wxMOCK", matchIfMissing = true)
public class WxMockPhoneAdapter implements WxPhoneAdapter {

    /** mock 固定手机号（与 mp 端 confirm.vue / profile.vue 的 wxMOCK 占位一致）。 */
    private static final String MOCK_PHONE = "13800000000";

    @Override
    public String code2Phone(String code) {
        log.info("[wx-phone-mock] code={} → mock phone {}", code, MOCK_PHONE);
        return MOCK_PHONE;
    }

    @Override
    public String channel() {
        return "mock";
    }
}
