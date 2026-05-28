package org.dromara.gz.ord.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * gz-ord 模块（V1.1 预定货品）hello world 占位 Controller
 * <p>
 * D01 GZ-SYS-001 脚手架阶段：模块先建（避免 V1.1 启动时再改根 pom 反复挪动），仅 hello 占位。
 * V1.1 起 GZ-ORD-* 系列 ticket 会在本模块填入业务 Controller。
 * </p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@RestController
@RequestMapping("/gz/ord")
public class OrdHelloController {

    @SaIgnore
    @GetMapping("/hello")
    public R<Map<String, String>> hello() {
        log.info("[gz-ord] hello endpoint hit");
        return R.ok(Map.of("msg", "gz-ord ok"));
    }
}
