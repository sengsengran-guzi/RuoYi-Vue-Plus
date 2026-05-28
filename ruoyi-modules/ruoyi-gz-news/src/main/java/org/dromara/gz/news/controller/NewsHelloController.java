package org.dromara.gz.news.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * gz-news 模块 hello world 占位 Controller
 * <p>
 * D01 GZ-SYS-001 脚手架阶段：验证模块已被 ruoyi-admin 启动加载。
 * 后续 ticket（GZ-NEWS-001 分类 / GZ-NEWS-002 文章 CMS 等）会在本模块加入真实业务 Controller。
 * </p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@RestController
@RequestMapping("/gz/news")
public class NewsHelloController {

    @SaIgnore
    @GetMapping("/hello")
    public R<Map<String, String>> hello() {
        log.info("[gz-news] hello endpoint hit");
        return R.ok(Map.of("msg", "gz-news ok"));
    }
}
