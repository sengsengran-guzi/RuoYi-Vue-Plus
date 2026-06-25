package org.dromara.gz.common.cs.controller.applet;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.gz.common.cs.domain.vo.GzCustomerServiceConfigVO;
import org.dromara.gz.common.cs.service.IGzCustomerServiceConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客服配置 mp 端（GZ-SYS-004B）。
 *
 * <p><b>需登录</b>（Kevin 拍板：客服信息仅登录用户可见）—— 不标 {@link cn.dev33.satoken.annotation.SaIgnore}，
 * 走全局 sa-token 鉴权；登录态 → TenantHelper 自动锁定当前用户租户（mp 用户即租户 1001），
 * 读到的就是该租户那一行客服配置。前缀 {@code /app/gz/customer-service}。</p>
 *
 * <p>mp 前端（useCustomerServiceConfig）匿名时不发请求（避免 401 跳登录），登录后调本端点拿真实值。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-004B)
 */
@RestController
@RequestMapping("/app/gz/customer-service")
@RequiredArgsConstructor
public class GzCustomerServiceMpController {

    private final IGzCustomerServiceConfigService configService;

    /**
     * 读当前登录租户的客服配置。
     *
     * <pre>
     * GET /app/gz/customer-service
     * 200 OK { "code":200, "data": { "wxKfId":"", "phone":"028-xxx", "wxId":"guzi_kf" } }
     * </pre>
     */
    @GetMapping
    public R<GzCustomerServiceConfigVO> get() {
        return R.ok(configService.getConfig());
    }
}
