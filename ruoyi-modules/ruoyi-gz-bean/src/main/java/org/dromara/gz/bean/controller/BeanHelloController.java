package org.dromara.gz.bean.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * gz-bean 模块 hello world 占位 Controller
 * <p>
 * D01 GZ-SYS-001 脚手架阶段：验证模块已被 ruoyi-admin 启动加载、Spring 扫描到该包、路由可访问。
 * 后续 ticket（GZ-BEAN-001 门店配置 / GZ-BEAN-002 时段模板 / GZ-BEAN-003 预约下单等）会在本模块加入真实业务 Controller。
 * </p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@RestController
@RequestMapping("/gz/bean")
public class BeanHelloController {

    @SaIgnore
    @GetMapping("/hello")
    public R<Map<String, String>> hello() {
        log.info("[gz-bean] hello endpoint hit");
        return R.ok(Map.of("msg", "gz-bean ok"));
    }
}
