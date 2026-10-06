package org.dromara.gz.jp.controller.applet;
import cn.dev33.satoken.annotation.SaIgnore;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.ratelimiter.annotation.RateLimiter;
import org.dromara.gz.jp.domain.vo.*;
import org.dromara.gz.jp.service.internal.GzJpSnapReadService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@SaIgnore @RestController @RequiredArgsConstructor @RequestMapping("/app/gz/jp/snap")
public class GzJpSnapMpController {
    private final GzJpSnapReadService service;
    private final org.dromara.gz.jp.service.internal.GzJpSnapRateIdentity rateIdentity;
    @GetMapping("/search")
    @RateLimiter(key="#{@gzJpSnapRateIdentity.key()}", time=60, count=20, message="SNAP_TOO_FAST")
    public R<GzJpSnapSearchVO> search(@RequestParam String q, @RequestParam(defaultValue="created_desc") String sort, @RequestParam(defaultValue="1") int page) { return R.ok(service.search(q, sort, page)); }
    @GetMapping("/item/{externalId}")
    @RateLimiter(key="#{@gzJpSnapRateIdentity.key()}", time=60, count=30, message="SNAP_TOO_FAST")
    public R<GzJpSnapDetailVO> item(@PathVariable String externalId) { rateIdentity.checkDetail(); return R.ok(service.item(externalId)); }
    @GetMapping("/hot-terms") public R<List<String>> hotTerms() { return R.ok(service.hotTerms()); }
}
