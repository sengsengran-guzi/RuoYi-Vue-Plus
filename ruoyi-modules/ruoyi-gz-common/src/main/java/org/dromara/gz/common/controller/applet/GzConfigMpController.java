package org.dromara.gz.common.controller.applet;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.service.ConfigService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-BEAN-010 mp 端公开 sys_config 读取 Controller（C 端）。
 *
 * <p>URL 前缀 {@code /app/gz/common/config}（与 sensenran C 端 mp 前缀对齐 — 见 WxLoginController 注释）。</p>
 *
 * <p><b>为何不直接调 ruoyi 自带 {@code /system/config/configKey/{key}}</b>：ruoyi 自带
 * {@code SysConfigController} 默认要求 admin 登录态（未标 {@code @SaIgnore}），而本端点服务于
 * mp 落地页运营素材（banner / 文案），需要<b>未登录匿名用户也能读</b>（GZ-BEAN-010 AC 6 + 强约束 #2：
 * 落地页可未登录浏览）。给 ruoyi 自带 controller 加 {@code @SaIgnore} 会污染 ruoyi 自带模块源码
 * （CLAUDE.md §6 #1 禁止），故新建本薄包装 controller 用 {@link SaIgnore} 放行。</p>
 *
 * <p>底层复用 ruoyi-common-core 的 {@link ConfigService}（system 模块 {@code SysConfigServiceImpl}
 * 实现的跨模块 SPI），不依赖 ruoyi-system 模块，符合 gz-common 现有依赖边界。</p>
 *
 * <p><b>安全边界</b>：本端点仅暴露<b>运营素材类</b>配置（banner / 文案），key 由前端硬编码常量传入；
 * 不暴露 {@code config_value} 为密钥 / 商户证书的敏感 key（敏感配置走环境变量，不入 DB — doc/11 §10.4）。</p>
 *
 * <p>关联文档：doc/11 §10.4 sys_config / doc/10 §3 N1 拼豆落地页 / GZ-BEAN-010 AC 2/6</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-010)
 */
@Slf4j
@SaIgnore
@Validated
@RestController
@RequestMapping("/app/gz/common/config")
@RequiredArgsConstructor
public class GzConfigMpController {

    private final ConfigService configService;

    /**
     * 按 key 读取单个 sys_config 配置值（mp 落地页运营素材）。
     *
     * <pre>
     * GET /app/gz/common/config/get?key=gz.bean.home.banner
     * （无需 token — @SaIgnore 公开）
     *
     * 200 OK
     * {
     *   "code": 200,
     *   "msg": "操作成功",
     *   "data": "{\"imageUrl\":\"\",\"title\":\"成都拼豆店开张啦\",\"link\":\"\"}"
     * }
     * </pre>
     *
     * <p>data 为 sys_config.config_value 原始字符串（banner 场景是 JSON 串，由 mp 前端 JSON.parse）；
     * key 不存在时 ruoyi {@code getConfigValue} 返回空字符串，mp 前端据此降级占位图 + 默认文案。</p>
     *
     * @param key sys_config 的 config_key（前端硬编码常量，如 gz.bean.home.banner）
     * @return R&lt;String&gt;，data 是配置值字符串（可能为空串）
     */
    @GetMapping("/get")
    public R<String> get(@RequestParam("key") @NotBlank String key) {
        String value = configService.getConfigValue(key);
        // value 必须放 data（R.ok(String) 会命中 msg 重载导致 data 恒 null，mp 落地页 banner/文案读 data 全空）
        return R.ok("操作成功", value == null ? "" : value);
    }
}
