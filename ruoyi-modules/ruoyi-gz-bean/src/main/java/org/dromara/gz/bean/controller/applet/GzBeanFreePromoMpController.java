package org.dromara.gz.bean.controller.applet;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.gz.bean.domain.vo.GzBeanFreePromoStatusVO;
import org.dromara.gz.bean.service.IGzBeanFreePromoService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-BEAN-025 拼豆前 N 名免费促销提示（mp 端，ADR-0015 §4 / doc/11 §3.11）。
 *
 * <p>路径 {@code /app/gz/bean/free-promo}。<b>匿名可读</b>（{@link SaIgnore}）：促销 banner 是首页 /
 * 拼豆落地页引流提示，游客 browse-first 即可见（与 store/news/gacha 一致），无敏感数据。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-025)
 */
@SaIgnore
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/bean/free-promo")
public class GzBeanFreePromoMpController {

    private final IGzBeanFreePromoService freePromoService;

    /**
     * GET /app/gz/bean/free-promo/status?storeId — 促销提示状态。
     *
     * <pre>
     * 200 OK
     * { "code": 200, "data": {
     *     "enabled": true,        // false → mp 不显 banner、显真实价格
     *     "freeCount": 10,        // 每周期免费名额 N
     *     "remaining": 3,         // 本周期剩余（下限 0）；>0 才显 banner
     *     "periodLabel": "今日"   // 今日 / 本周 / 本周期；enabled=false 时 null
     * } }
     * </pre>
     */
    @GetMapping("/status")
    public R<GzBeanFreePromoStatusVO> status(@NotNull(message = "门店 ID 不能为空") @RequestParam Long storeId) {
        return R.ok(freePromoService.getStatus(storeId));
    }
}
