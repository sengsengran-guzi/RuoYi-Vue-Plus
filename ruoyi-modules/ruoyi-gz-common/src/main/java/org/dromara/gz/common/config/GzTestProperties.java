package org.dromara.gz.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * mp 真机自测入口开关（绑定 {@code gz.test}）。
 *
 * <p>后端线上为 prod 环境，自测端点（/app/gz/test/**）会创建真实 1 分订单 / 真实打款，
 * 故默认 <b>false</b>（关闭=端点拒绝）。Kevin 真机测试窗口时由运维注入 {@code GZ_TEST_ENABLED=true}
 * 重启开启，测完关回。小程序侧仅体验版显示「测试」tab（{@code VITE_SHOW_TEST_TAB}）。</p>
 *
 * @author kevin-coder (sensenran-guzi · 真机自测页)
 */
@Data
@Component
@ConfigurationProperties(prefix = "gz.test")
public class GzTestProperties {

    /** 总开关：true 才放行 /app/gz/test/** 自测端点（默认 false，防生产被刷真实订单/打款） */
    private boolean enabled = false;
}
